#!/usr/bin/env python3
"""Codex 5h-window quota primer: reference implementation + algorithm self-test.

Runs on Termux (Android) or desktop. The scheduling algorithm here is the exact
same one implemented in the Android app (core/Scheduler.kt).

Usage:
    python codex_quota.py --self-test          # verify scheduling algorithm
    python codex_quota.py --plan               # print today's activation chain
    python codex_quota.py --usage              # query quota (read-only)
    python codex_quota.py --once               # one cycle: check -> prime if due
    python codex_quota.py --loop               # run scheduler forever
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from typing import Optional

# ---------------------------------------------------------------- constants

CHATGPT_BASE = "https://chatgpt.com/backend-api"
USAGE_PATH = "/wham/usage"
RESPONSES_PATH = "/codex/responses"
TOKEN_URL = "https://auth.openai.com/oauth/token"
CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
ORIGINATOR = "codex_cli_rs"
USER_AGENT = "codex_cli_rs/0.9.0"
AUTH_FILE = os.path.expanduser("~/.codex/auth.json")
STATE_FILE = os.path.expanduser("~/.codex-quota-primer/state.json")


# ---------------------------------------------------------------- algorithm


@dataclass(frozen=True)
class Config:
    """All times are local wall-clock times."""

    start_hour: int = 5
    start_minute: int = 30
    cutoff_hour: int = 22
    cutoff_minute: int = 0
    window_hours: float = 5.0
    grace_seconds: int = 60

    @property
    def start(self) -> dt.time:
        return dt.time(self.start_hour, self.start_minute)

    @property
    def cutoff(self) -> dt.time:
        return dt.time(self.cutoff_hour, self.cutoff_minute)

    @property
    def window(self) -> dt.timedelta:
        return dt.timedelta(hours=self.window_hours)

    @property
    def grace(self) -> dt.timedelta:
        return dt.timedelta(seconds=self.grace_seconds)


def in_active_span(t: dt.datetime, cfg: Config) -> bool:
    """True when this wall-clock time is inside [start, cutoff] for its own day."""
    clock = t.timetz().replace(tzinfo=None)
    return cfg.start <= clock <= cfg.cutoff


def next_activation(now: dt.datetime, reset_at: Optional[dt.datetime], cfg: Config) -> dt.datetime:
    """Return the next moment the primer should fire.

    reset_at: the primary window's reset instant reported by the server, or None
              when unknown (fresh install / first run).
    """
    if reset_at is not None:
        candidate = reset_at + cfg.grace
    elif in_active_span(now, cfg):
        # No data yet but we are inside the span: prime immediately.
        candidate = now
    else:
        candidate = dt.datetime.combine(now.date(), cfg.start)
        if candidate < now:
            candidate += dt.timedelta(days=1)

    # Missed while the phone was off / asleep: never fire in the past.
    effective = max(candidate, now)

    if in_active_span(effective, cfg):
        return effective
    if effective.timetz().replace(tzinfo=None) < cfg.start:
        return dt.datetime.combine(effective.date(), cfg.start)
    return dt.datetime.combine(effective.date() + dt.timedelta(days=1), cfg.start)


def daily_chain(day: dt.date, cfg: Config) -> list[dt.datetime]:
    """Activation instants for one day, e.g. 05:30 / 10:30 / 15:30 / 20:30."""
    out: list[dt.datetime] = []
    t = dt.datetime.combine(day, cfg.start)
    while in_active_span(t, cfg):
        out.append(t)
        t = t + cfg.window
        if t.date() != day:
            break
    return out


# ---------------------------------------------------------------- http


@dataclass
class Creds:
    access_token: str
    refresh_token: Optional[str]
    account_id: Optional[str]


def load_creds(path: str = AUTH_FILE) -> Creds:
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    tokens = data.get("tokens") or {}
    access = tokens.get("access_token") or data.get("OPENAI_API_KEY")
    if not access:
        raise SystemExit("no access_token in %s" % path)
    return Creds(access, tokens.get("refresh_token"), tokens.get("account_id"))


def _headers(creds: Creds, sse: bool = False) -> dict[str, str]:
    h = {
        "Authorization": "Bearer " + creds.access_token,
        "originator": ORIGINATOR,
        "User-Agent": USER_AGENT,
        "Accept": "text/event-stream" if sse else "application/json",
    }
    if creds.account_id:
        h["chatgpt-account-id"] = creds.account_id
    return h


@dataclass
class RateWindow:
    used_percent: float
    window_seconds: int
    reset_at: Optional[dt.datetime]


@dataclass
class Snapshot:
    plan_type: str
    primary: RateWindow
    secondary: Optional[RateWindow]
    reset_credits: int
    limit_reached: bool
    raw: dict


def _window(node: Optional[dict]) -> Optional[RateWindow]:
    if not node:
        return None
    reset_at = node.get("reset_at")
    return RateWindow(
        used_percent=float(node.get("used_percent", 0)),
        window_seconds=int(node.get("limit_window_seconds", 0)),
        reset_at=dt.datetime.fromtimestamp(reset_at).astimezone() if reset_at else None,
    )


def fetch_usage(creds: Creds, timeout: int = 30) -> Snapshot:
    req = urllib.request.Request(CHATGPT_BASE + USAGE_PATH, headers=_headers(creds))
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = json.loads(r.read().decode())
    rl = data.get("rate_limit") or {}
    primary = _window(rl.get("primary_window"))
    if primary is None:
        raise SystemExit("usage payload has no primary_window")
    return Snapshot(
        plan_type=data.get("plan_type", "unknown"),
        primary=primary,
        secondary=_window(rl.get("secondary_window")),
        reset_credits=int((data.get("rate_limit_reset_credits") or {}).get("available_count", 0)),
        limit_reached=bool(rl.get("limit_reached")),
        raw=data,
    )


def prime(creds: Creds, model: str, timeout: int = 90) -> tuple[bool, dict[str, str]]:
    """Send the smallest possible turn to start a fresh 5h window.

    Returns (ok, response_headers). The x-codex-* headers carry the authoritative
    reset timestamps, so one request both primes and reports the new window.
    """
    body = {
        "model": model,
        "instructions": "Reply with exactly OK.",
        "input": [
            {
                "type": "message",
                "role": "user",
                "content": [{"type": "input_text", "text": "Reply with exactly OK."}],
            }
        ],
        "stream": True,
        "store": False,
        "tools": [],
        "tool_choice": "auto",
        "parallel_tool_calls": False,
        "reasoning": {"effort": "low", "summary": "auto"},
        "include": [],
        "prompt_cache_key": str(uuid.uuid4()),
    }
    h = _headers(creds, sse=True)
    h["Content-Type"] = "application/json"
    h["OpenAI-Beta"] = "responses=experimental"
    h["session_id"] = str(uuid.uuid4())
    req = urllib.request.Request(
        CHATGPT_BASE + RESPONSES_PATH, data=json.dumps(body).encode(), method="POST", headers=h
    )
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:500]
        print("  prime failed HTTP %s: %s" % (e.code, detail), file=sys.stderr)
        return False, {}
    hdrs = {k.lower(): v for k, v in resp.headers.items()}
    # Read until the model actually starts producing, then hang up to save quota.
    for raw in resp:
        if raw.startswith(b"event: response.output_item") or raw.startswith(b"event: response.completed"):
            break
    resp.close()
    return resp.status == 200, hdrs


def header_reset(hdrs: dict[str, str]) -> Optional[dt.datetime]:
    value = hdrs.get("x-codex-primary-reset-at")
    if not value:
        return None
    return dt.datetime.fromtimestamp(int(value)).astimezone()


def refresh_token(creds: Creds, path: str = AUTH_FILE) -> Creds:
    """Exchange the refresh token for a new access token and persist it."""
    payload = urllib.parse.urlencode(
        {
            "grant_type": "refresh_token",
            "refresh_token": creds.refresh_token,
            "client_id": CLIENT_ID,
            "scope": "openid profile email offline_access",
        }
    ).encode()
    req = urllib.request.Request(
        TOKEN_URL,
        data=payload,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.loads(r.read().decode())
    new = Creds(
        access_token=data["access_token"],
        refresh_token=data.get("refresh_token", creds.refresh_token),
        account_id=creds.account_id,
    )
    with open(path, "r", encoding="utf-8") as f:
        raw = json.load(f)
    raw.setdefault("tokens", {})
    raw["tokens"]["access_token"] = new.access_token
    raw["tokens"]["refresh_token"] = new.refresh_token
    raw["last_refresh"] = dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(raw, f, indent=2)
    return new


# ---------------------------------------------------------------- cycle


def fmt(t: Optional[dt.datetime]) -> str:
    return t.strftime("%m-%d %H:%M:%S") if t else "-"


def cycle(cfg: Config, model: str, creds: Creds, do_prime: bool = True) -> Optional[dt.datetime]:
    now = dt.datetime.now().astimezone()
    snap = fetch_usage(creds)
    primary = snap.primary
    print(
        "[%s] plan=%s primary=%.0f%% reset=%s secondary=%s"
        % (
            fmt(now),
            snap.plan_type,
            primary.used_percent,
            fmt(primary.reset_at),
            ("%.0f%% reset=%s" % (snap.secondary.used_percent, fmt(snap.secondary.reset_at)))
            if snap.secondary
            else "-",
        )
    )

    due = primary.reset_at is None or primary.reset_at <= now
    if due and do_prime:
        if snap.limit_reached:
            print("  weekly limit reached, skip priming")
        else:
            ok, hdrs = prime(creds, model)
            new_reset = header_reset(hdrs)
            if ok and new_reset:
                print("  primed, new primary reset=%s" % fmt(new_reset))
                primary.reset_at = new_reset
            else:
                print("  prime did not report a reset, re-querying usage")
                primary.reset_at = fetch_usage(creds).primary.reset_at
    elif due:
        print("  window is due but priming disabled (dry run)")

    nxt = next_activation(dt.datetime.now().astimezone(), primary.reset_at, cfg)
    print("  next activation: %s (in %s)" % (fmt(nxt), nxt - dt.datetime.now().astimezone()))
    return nxt


# ---------------------------------------------------------------- self test


def self_test() -> int:
    failures = 0

    def check(name: str, got, want) -> None:
        nonlocal failures
        if got != want:
            failures += 1
            print("FAIL %s\n  got  %s\n  want %s" % (name, got, want))
        else:
            print("ok   %s -> %s" % (name, got))

    # The user's example: start 05:00, cutoff 22:00, 5h windows.
    ex = Config(start_hour=5, start_minute=0)
    chain = daily_chain(dt.date(2026, 11, 13), ex)
    check("example chain", [c.strftime("%H:%M") for c in chain], ["05:00", "10:00", "15:00", "20:00"])

    day = dt.date(2026, 11, 13)
    five = dt.datetime.combine(day, dt.time(5, 0))
    for i, want in enumerate(["05:00", "10:00", "15:00", "20:00"]):
        t = five + ex.window * i
        check("step %d" % i, t.strftime("%H:%M"), want)

    # After the 20:00 activation the window resets at 01:00 next day -> skipped.
    reset_100 = dt.datetime.combine(day + dt.timedelta(days=1), dt.time(1, 0))
    nxt = next_activation(dt.datetime.combine(day, dt.time(20, 0, 5)), reset_100, ex)
    check("01:00 skipped to next 05:00", nxt, dt.datetime.combine(day + dt.timedelta(days=1), dt.time(5, 0)))

    # 05:30 default: chain is 05:30 / 10:30 / 15:30 / 20:30
    d = Config()
    check(
        "default chain",
        [c.strftime("%H:%M") for c in daily_chain(day, d)],
        ["05:30", "10:30", "15:30", "20:30"],
    )
    reset_130 = dt.datetime.combine(day + dt.timedelta(days=1), dt.time(1, 30))
    check(
        "01:30 skipped",
        next_activation(dt.datetime.combine(day, dt.time(20, 30)), reset_130, d),
        dt.datetime.combine(day + dt.timedelta(days=1), dt.time(5, 30)),
    )

    # The span rule applies to the moment the primer actually fires, not to the
    # reset instant, so a reset at 22:00 (fire at 22:01) is already out of span.
    check(
        "reset == cutoff skipped",
        next_activation(dt.datetime.combine(day, dt.time(17, 0)), dt.datetime.combine(day, dt.time(22, 0)), d),
        dt.datetime.combine(day + dt.timedelta(days=1), dt.time(5, 30)),
    )
    check(
        "reset 21:58 fires at 21:59",
        next_activation(dt.datetime.combine(day, dt.time(17, 0)), dt.datetime.combine(day, dt.time(21, 58)), d),
        dt.datetime.combine(day, dt.time(21, 59)),
    )
    check(
        "reset == cutoff+1m skipped",
        next_activation(dt.datetime.combine(day, dt.time(17, 0)), dt.datetime.combine(day, dt.time(22, 1)), d),
        dt.datetime.combine(day + dt.timedelta(days=1), dt.time(5, 30)),
    )

    # Missed while asleep: primer fires immediately when still inside the span.
    check(
        "missed reset caught up",
        next_activation(dt.datetime.combine(day, dt.time(12, 0)), dt.datetime.combine(day, dt.time(10, 5)), d),
        dt.datetime.combine(day, dt.time(12, 0)),
    )
    # Missed and now past cutoff: wait for tomorrow morning.
    check(
        "missed after cutoff",
        next_activation(dt.datetime.combine(day, dt.time(23, 10)), dt.datetime.combine(day, dt.time(20, 30)), d),
        dt.datetime.combine(day + dt.timedelta(days=1), dt.time(5, 30)),
    )
    # Fresh install at 03:00 -> today 05:30.
    check(
        "fresh install before start",
        next_activation(dt.datetime.combine(day, dt.time(3, 0)), None, d),
        dt.datetime.combine(day, dt.time(5, 30)),
    )
    # Fresh install at 12:00 (inside the span) -> prime right away.
    check(
        "fresh install inside span",
        next_activation(dt.datetime.combine(day, dt.time(12, 0)), None, d),
        dt.datetime.combine(day, dt.time(12, 0)),
    )
    # Fresh install at 23:00 -> tomorrow 05:30.
    check(
        "fresh install after cutoff",
        next_activation(dt.datetime.combine(day, dt.time(23, 0)), None, d),
        dt.datetime.combine(day + dt.timedelta(days=1), dt.time(5, 30)),
    )

    # 4h windows: 05:00 / 09:00 / 13:00 / 17:00 / 21:00, then 01:00 skipped.
    four = Config(start_hour=5, start_minute=0, window_hours=4)
    check(
        "4h chain",
        [c.strftime("%H:%M") for c in daily_chain(day, four)],
        ["05:00", "09:00", "13:00", "17:00", "21:00"],
    )

    print("\n%s (%d failure%s)" % ("ALL PASS" if failures == 0 else "FAILURES", failures, "" if failures == 1 else "s"))
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--plan", action="store_true")
    ap.add_argument("--usage", action="store_true")
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--loop", action="store_true")
    ap.add_argument("--dry-run", action="store_true", help="never send the priming request")
    ap.add_argument("--model", default=os.environ.get("CODEX_QUOTA_MODEL", "gpt-6-astra"))
    ap.add_argument("--start", default="05:30", help="first activation HH:MM")
    ap.add_argument("--cutoff", default="22:00", help="do not activate at/after HH:MM")
    ap.add_argument("--window-hours", type=float, default=5.0)
    ap.add_argument("--auth", default=AUTH_FILE)
    a = ap.parse_args()

    if a.self_test:
        return self_test()

    cfg = Config(
        start_hour=int(a.start.split(":")[0]),
        start_minute=int(a.start.split(":")[1]),
        cutoff_hour=int(a.cutoff.split(":")[0]),
        cutoff_minute=int(a.cutoff.split(":")[1]),
        window_hours=a.window_hours,
    )

    if a.plan:
        for i in range(2):
            d = dt.date.today() + dt.timedelta(days=i)
            print("%s: %s" % (d, ", ".join(c.strftime("%H:%M") for c in daily_chain(d, cfg))))
        return 0

    creds = load_creds(a.auth)

    if a.usage:
        s = fetch_usage(creds)
        print(json.dumps(s.raw, indent=2, ensure_ascii=False))
        return 0

    if a.once:
        cycle(cfg, a.model, creds, do_prime=not a.dry_run)
        return 0

    if a.loop:
        while True:
            nxt = cycle(cfg, a.model, creds, do_prime=not a.dry_run)
            wait = (nxt - dt.datetime.now().astimezone()).total_seconds()
            wait = max(30, min(wait, 6 * 3600))
            time.sleep(wait)
        return 0

    ap.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
