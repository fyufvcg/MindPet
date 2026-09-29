#!/usr/bin/env python3
"""Create a fresh, deterministic 100-case Java curator retest dataset."""

from __future__ import annotations

import hashlib
import json
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


HERE = Path(__file__).resolve().parent
DATA_DIR = HERE / "data"
DATASET = DATA_DIR / "retest-100-expected-profile-40-20260929.jsonl"
MANIFEST = DATA_DIR / "retest-100-expected-profile-40-20260929.manifest.json"
ZONE = "Asia/Shanghai"
BASE = datetime(2026, 9, 20, 2, 0, tzinfo=timezone.utc)

NEW_CITIES = ["贵阳", "太原", "郑州", "石家庄", "大连", "无锡", "东莞", "绍兴", "南通", "珠海"]
OCCUPATIONS = ["机械工程师", "财务顾问", "运营专员", "兽医", "建筑师", "研究员", "采购主管", "翻译", "测试工程师", "编辑"]
OLD_PROJECTS = [f"旧版服务项目{i:02d}" for i in range(1, 11)]
NEW_PROJECTS = [f"新版分析项目{i:02d}" for i in range(1, 11)]
EVENTS = ["技术评审", "门诊复查", "资格考试", "方案演示", "团队培训", "作品提交", "客户访谈", "设备验收", "论文答辩", "课程汇报"]
AMBIGUOUS_TIMES = ["过几天", "过些天", "最近", "以后", "下个月左右", "几天后",
                   "过几天", "最近", "以后", "几天后"]


def iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def make_turn(sample_id: str, number: int, text: str, day_offset: int, session: int = 1) -> dict[str, str]:
    return {
        "turn_id": f"{sample_id}-t{number}",
        "session_id": f"{sample_id}-s{session}",
        "occurred_at": iso(BASE + timedelta(days=day_offset)),
        "timezone": ZONE,
        "user": text,
        "assistant": "好的，我记下了。",
    }


def make_fact(predicate: str, value: str, scope: str, assertion: str,
              confidence: float, turn: dict[str, str], *, raw_time: str = "") -> dict[str, Any]:
    result: dict[str, Any] = {
        "predicate": predicate,
        "value": value,
        "scope": scope,
        "assertion": assertion,
        "confidence": confidence,
        "source_turn_id": turn["turn_id"],
        "evidence": turn["user"],
    }
    if raw_time:
        result["time"] = {"raw": raw_time}
    return result


def expected_fact(predicate: str, value: str, scope: str, assertion: str = "observed") -> dict[str, str]:
    return {"predicate": predicate, "value": value, "scope": scope, "assertion": assertion}


def sample(sample_id: str, category: str, turns: list[dict[str, str]], facts: list[dict[str, Any]],
           expected_facts: list[dict[str, str]], *, profile: dict[str, str] | None = None,
           expected_times: list[dict[str, str]] | None = None,
           sensitive: bool = False) -> dict[str, Any]:
    return {
        "sample_id": sample_id,
        "user_id": f"retest-user-{sample_id}",
        "case_type": category,
        "synthetic": True,
        "processing_at": iso(BASE + timedelta(days=30)),
        "turns": turns,
        "proposal": {"facts": facts},
        "expected_facts": expected_facts,
        "expected_profile": profile or {},
        "expected_times": expected_times or [],
        "sensitive": sensitive,
    }


def generate() -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []

    # Ten stable home facts: ten expected profile slots.
    for i, city in enumerate(NEW_CITIES):
        sid = f"rt-stable-{i + 1:02d}"
        turn = make_turn(sid, 1, f"我的固定居所在{city}，以后回家都回这里。", i)
        rows.append(sample(sid, "stable_home", [turn],
            [make_fact("home_location", city, "stable", "observed", 0.96, turn)],
            [expected_fact("home_location", city, "stable")], profile={"home_location": city}))

    # Ten current occupations: ten expected profile slots.
    for i, occupation in enumerate(OCCUPATIONS):
        sid = f"rt-occupation-{i + 1:02d}"
        turn = make_turn(sid, 1, f"我现在的工作是{occupation}，这是我目前的职业。", i + 10)
        rows.append(sample(sid, "current_occupation", [turn],
            [make_fact("occupation_current", occupation, "current", "observed", 0.95, turn)],
            [expected_fact("occupation_current", occupation, "current")],
            profile={"occupation_current": occupation}))

    # Ten out-of-order fact proposals: ten expected profile slots.
    for i in range(10):
        sid = f"rt-location-conflict-{i + 1:02d}"
        old_city = NEW_CITIES[i]
        new_city = NEW_CITIES[(i + 3) % len(NEW_CITIES)]
        old_turn = make_turn(sid, 1, f"我现在住在{old_city}。", i + 20, 1)
        new_turn = make_turn(sid, 2, f"后来搬家了，我现在住在{new_city}。", i + 24, 2)
        facts = [
            make_fact("current_location", new_city, "current", "observed", 0.98, new_turn),
            make_fact("current_location", old_city, "current", "observed", 0.94, old_turn),
        ]
        expected = [expected_fact("current_location", old_city, "current"),
                    expected_fact("current_location", new_city, "current")]
        rows.append(sample(sid, "event_time_conflict", [old_turn, new_turn], facts, expected,
            profile={"current_location": new_city}))

    # Ten corrected project facts: ten expected profile slots.
    for i, (old_project, new_project) in enumerate(zip(OLD_PROJECTS, NEW_PROJECTS)):
        sid = f"rt-project-correction-{i + 1:02d}"
        old_turn = make_turn(sid, 1, f"我目前负责{old_project}。", i + 35, 1)
        new_turn = make_turn(sid, 2, f"更正一下，我现在改为负责{new_project}。", i + 39, 2)
        facts = [
            make_fact("current_project", old_project, "current", "observed", 0.93, old_turn),
            make_fact("current_project", new_project, "current", "observed", 0.98, new_turn),
        ]
        expected = [expected_fact("current_project", old_project, "current"),
                    expected_fact("current_project", new_project, "current")]
        rows.append(sample(sid, "project_correction", [old_turn, new_turn], facts, expected,
            profile={"current_project": new_project}))

    # Ten historical facts must be retained without entering the current profile.
    for i, occupation in enumerate(reversed(OCCUPATIONS)):
        sid = f"rt-history-{i + 1:02d}"
        turn = make_turn(sid, 1, f"我以前做过{occupation}，那是过去的工作经历。", i + 45)
        rows.append(sample(sid, "historical_only", [turn],
            [make_fact("occupation_current", occupation, "historical", "observed", 0.92, turn)],
            [expected_fact("occupation_current", occupation, "historical")]))

    # Ten tentative future moves must not populate the present-location slot.
    for i, city in enumerate(reversed(NEW_CITIES)):
        sid = f"rt-plan-{i + 1:02d}"
        turn = make_turn(sid, 1, f"我以后可能搬去{city}，目前还没有决定。", i + 55)
        raw = "以后"
        rows.append(sample(sid, "tentative_plan", [turn],
            [make_fact("current_location", city, "planned", "possible", 0.76, turn, raw_time=raw)],
            [expected_fact("current_location", city, "planned", "possible")],
            expected_times=[{"source_turn_id": turn["turn_id"], "raw": raw, "status": "ambiguous"}]))

    # Ten explicit event dates exercise exact time normalization.
    for i, event in enumerate(EVENTS):
        sid = f"rt-explicit-date-{i + 1:02d}"
        turn_day = i + 65
        event_date = (BASE + timedelta(days=turn_day + 12)).date().isoformat()
        turn = make_turn(sid, 1, f"我计划在{event_date}参加{event}。", turn_day)
        rows.append(sample(sid, "explicit_event_time", [turn],
            [make_fact("event", event, "episodic", "observed", 0.94, turn, raw_time=event_date)],
            [expected_fact("event", event, "episodic")],
            expected_times=[{"source_turn_id": turn["turn_id"], "raw": event_date,
                             "status": "resolved", "normalized_start": event_date}]))

    # Ten vague event times must stay ambiguous.
    for i, raw in enumerate(AMBIGUOUS_TIMES):
        sid = f"rt-ambiguous-time-{i + 1:02d}"
        event = EVENTS[(i + 3) % len(EVENTS)]
        turn = make_turn(sid, 1, f"{raw}可能要参加{event}，具体哪天还没定。", i + 77)
        rows.append(sample(sid, "ambiguous_event_time", [turn],
            [make_fact("event", event, "episodic", "possible", 0.77, turn, raw_time=raw)],
            [expected_fact("event", event, "episodic", "possible")],
            expected_times=[{"source_turn_id": turn["turn_id"], "raw": raw, "status": "ambiguous"}]))

    # Ten explicit denials deactivate previously stored positive current-location facts.
    for i, city in enumerate(NEW_CITIES):
        sid = f"rt-negation-{i + 1:02d}"
        first = make_turn(sid, 1, f"我现在住在{city}。", i + 87, 1)
        second = make_turn(sid, 2, f"我其实没有住在{city}，刚才那句说错了，请不要记为现居地。", i + 89, 2)
        facts = [
            make_fact("current_location", city, "current", "observed", 0.95, first),
            make_fact("current_location", city, "current", "negated", 0.98, second),
        ]
        expected = [expected_fact("current_location", city, "current"),
                    expected_fact("current_location", city, "current", "negated")]
        rows.append(sample(sid, "negation_deactivates_old_profile", [first, second], facts, expected))

    # Ten fake credentials verify that sensitive content is rejected.
    for i in range(10):
        sid = f"rt-sensitive-{i + 1:02d}"
        fake_key = f"sk-FAKE-TEST-ONLY-NOT-A-CREDENTIAL-{i + 1:04d}"
        text = f"合成安全测试：这是虚构字符串，不是真实凭据 API_KEY={fake_key}，请不要保存。"
        turn = make_turn(sid, 1, text, i + 93)
        rows.append(sample(sid, "fake_credential_interference", [turn],
            [make_fact("preference", fake_key, "stable", "observed", 0.99, turn)], [], sensitive=True))

    assert len(rows) == 100, f"expected 100 cases; got {len(rows)}"
    assert sum(len(row["expected_profile"]) for row in rows) == 40
    assert len({row["sample_id"] for row in rows}) == 100
    return rows


def main() -> None:
    rows = generate()
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    content = "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows)
    DATASET.write_text(content, encoding="utf-8", newline="\n")
    manifest = {
        "dataset": DATASET.name,
        "sha256": hashlib.sha256(content.encode("utf-8")).hexdigest(),
        "synthetic_only": True,
        "sample_count": len(rows),
        "expected_profile_slots": sum(len(row["expected_profile"]) for row in rows),
        "samples_with_expected_profile": sum(bool(row["expected_profile"]) for row in rows),
        "case_type_counts": dict(sorted(Counter(row["case_type"] for row in rows).items())),
        "expected_fact_count": sum(len(row["expected_facts"]) for row in rows),
        "expected_time_count": sum(len(row["expected_times"]) for row in rows),
        "data_source": "generated locally by this standalone experiment script; no user or application data",
        "llm_calls": 0,
        "application_database_access": False,
    }
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
