#!/usr/bin/env python3
"""Generate a deterministic, synthetic Memory Curator benchmark JSONL.

This generator uses only the Python standard library. It does not import MINDPET,
read application data, call a model, or write outside this experiment directory.
"""

from __future__ import annotations

import json
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


HERE = Path(__file__).resolve().parent
DATA_DIR = HERE / "data"
DATA_PATH = DATA_DIR / "synthetic-curator-benchmark-1200.jsonl"
MANIFEST_PATH = DATA_DIR / "synthetic-curator-benchmark-1200.manifest.json"

QUOTAS = {
    "stable_profile": 120,
    "current_state": 120,
    "historical_facts": 120,
    "state_conflicts": 120,
    "explicit_time": 140,
    "ambiguous_time": 100,
    "plans_and_preferences": 120,
    "negation_and_correction": 100,
    "sensitive_interference": 100,
    "cross_session": 160,
}

ALLOWED_SCOPES = {"current", "stable", "episodic", "planned", "historical"}
ALLOWED_ASSERTIONS = {
    "observed", "confirmed", "reported", "planned", "possible", "uncertain", "negated"
}

CITIES = [
    "宜兴", "南京", "苏州", "杭州", "成都", "西安", "武汉", "厦门",
    "青岛", "深圳", "广州", "北京", "上海", "合肥", "昆明", "宁波",
]
JOBS = ["软件工程师", "产品经理", "中学教师", "平面设计师", "数据分析师", "护士"]
PROJECTS = ["校园导航项目", "门店改造项目", "客户服务平台", "影像分析项目", "课程设计项目"]
PREFERENCES = ["清淡饮食", "中文回复", "徒步旅行", "提前列计划", "简短说明", "公共交通"]
EVENTS = ["项目评审", "复诊", "课程考试", "客户演示", "团队培训", "作品提交"]
AMBIGUOUS_TIMES = ["过几天", "过些天", "最近", "以后", "下个月左右", "几天后"]
RESOLVED_RELATIVE_TIMES = {"昨天": -1, "今天": 0, "明天": 1, "后天": 2, "大后天": 3}


def stamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def anchor(index: int) -> datetime:
    return datetime(2026, 9, 20, 2, 0, tzinfo=timezone.utc) + timedelta(days=index % 35)


def make_turn(
    sample_id: str,
    number: int,
    occurred_at: datetime,
    user_text: str,
    session_number: int = 1,
) -> dict[str, Any]:
    return {
        "turn_id": f"{sample_id}-t{number}",
        "session_id": f"{sample_id}-s{session_number}",
        "occurred_at": stamp(occurred_at),
        "timezone": "Asia/Shanghai",
        "user": user_text,
        "assistant": "好的，我记下了。",
    }


def fact(
    predicate: str,
    value: str,
    scope: str,
    assertion: str,
    confidence: float,
    source_turn_id: str,
    *,
    raw_time: str = "",
    raw_text: str = "",
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "predicate": predicate,
        "value": value,
        "scope": scope,
        "assertion": assertion,
        "confidence": confidence,
        "source_turn_id": source_turn_id,
    }
    if raw_time:
        result["time"] = {"raw": raw_time}
    if raw_text:
        result["raw_text"] = raw_text
    return result


def expected_fact(predicate: str, value: str, scope: str, assertion: str = "observed") -> dict[str, str]:
    return {
        "predicate": predicate,
        "value": value,
        "scope": scope,
        "assertion": assertion,
    }


def make_case(
    sample_id: str,
    category: str,
    user_number: int,
    turns: list[dict[str, Any]],
    proposals: list[dict[str, Any]],
    expected_facts: list[dict[str, str]],
    *,
    expected_profile: dict[str, str] | None = None,
    expected_times: list[dict[str, str]] | None = None,
    sensitive: bool = False,
    processing_at: datetime | None = None,
    allow_invalid_sources: bool = False,
    case_variant: str = "",
) -> dict[str, Any]:
    assert turns, sample_id
    valid_ids = {turn["turn_id"] for turn in turns}
    turns_by_id = {turn["turn_id"]: turn for turn in turns}
    for proposal_fact in proposals:
        assert proposal_fact["scope"] in ALLOWED_SCOPES, (sample_id, proposal_fact)
        assert proposal_fact["assertion"] in ALLOWED_ASSERTIONS, (sample_id, proposal_fact)
        if not allow_invalid_sources:
            assert proposal_fact["source_turn_id"] in valid_ids, (sample_id, proposal_fact)
        source_turn = turns_by_id.get(proposal_fact["source_turn_id"])
        if source_turn and not proposal_fact.get("evidence"):
            proposal_fact["evidence"] = source_turn["user"]
    result = {
        "sample_id": sample_id,
        "user_id": f"synthetic-user-{user_number + 1:03d}",
        "case_type": category,
        "synthetic": True,
        "processing_at": stamp(processing_at or datetime.fromisoformat(turns[-1]["occurred_at"].replace("Z", "+00:00"))),
        "turns": turns,
        "proposal": {"facts": proposals},
        "expected_facts": expected_facts,
        "expected_profile": expected_profile or {},
        "expected_times": expected_times or [],
        "sensitive": sensitive,
    }
    if case_variant:
        result["case_variant"] = case_variant
    return result


def add_synthetic_user_histories(rows: list[dict[str, Any]]) -> dict[str, int]:
    """Give each pseudonymous user 180–220 total turns across independent cases."""
    by_user: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        by_user.setdefault(row["user_id"], []).append(row)

    filler = [
        "今天聊了部电影，剧情还不错。",
        "刚看完一篇科普文章，记了几个新词。",
        "这周通勤路上听了一个播客。",
        "午餐吃了面条，味道普通。",
        "今天下午下了一阵雨。",
        "桌面整理完以后看起来清爽多了。",
        "刚才看到一张有趣的风景照片。",
        "周末商场里的人比平时多。",
        "今天读到一段关于海洋的介绍。",
        "晚上准备早点休息。",
    ]
    user_turn_totals: dict[str, int] = {}
    for user_index, user_id in enumerate(sorted(by_user)):
        user_rows = by_user[user_id]
        base_total = sum(len(row["turns"]) for row in user_rows)
        target_total = 180 + ((user_index * 17) % 41)
        additional = target_total - base_total
        quotient, remainder = divmod(additional, len(user_rows))
        for row_index, row in enumerate(user_rows):
            count = quotient + int(row_index < remainder)
            first_time = datetime.fromisoformat(row["turns"][0]["occurred_at"].replace("Z", "+00:00"))
            start = first_time - timedelta(days=count)
            context_turns = []
            for i in range(count):
                occurred = start + timedelta(days=i)
                context_turns.append({
                    "turn_id": f"{row['sample_id']}-context-{i + 1:02d}",
                    "session_id": f"{row['sample_id']}-context-session-{i // 5 + 1}",
                    "occurred_at": stamp(occurred),
                    "timezone": "Asia/Shanghai",
                    "user": filler[(user_index + row_index + i) % len(filler)],
                    "assistant": "嗯，收到。",
                })
            row["turns"] = context_turns + row["turns"]
        user_turn_totals[user_id] = sum(len(row["turns"]) for row in user_rows)
    return user_turn_totals


def generate() -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []

    for i in range(QUOTAS["stable_profile"]):
        sample_id = f"syn-{len(rows) + 1:04d}-stable"
        when = anchor(i)
        if i % 2:
            predicate, value = "occupation_current", JOBS[i % len(JOBS)]
            sentence = f"我目前的职业是{value}。"
        else:
            predicate, value = "home_location", CITIES[i % len(CITIES)]
            sentence = f"我长期居住的家在{value}。"
        scope = "stable" if predicate == "home_location" else "current"
        turn = make_turn(sample_id, 1, when, sentence)
        rows.append(make_case(
            sample_id, "stable_profile", i % 100, [turn],
            [fact(predicate, value, scope, "observed", 0.96, turn["turn_id"])],
            [expected_fact(predicate, value, scope)],
            expected_profile={predicate: value},
        ))

    for i in range(QUOTAS["current_state"]):
        sample_id = f"syn-{len(rows) + 1:04d}-current"
        when = anchor(i + 3)
        kind = i % 4
        if kind == 0:
            predicate, value, sentence = "current_location", CITIES[(i + 2) % len(CITIES)], f"我现在在{CITIES[(i + 2) % len(CITIES)]}工作。"
        elif kind == 1:
            predicate, value, sentence = "current_project", PROJECTS[i % len(PROJECTS)], f"我目前负责{PROJECTS[i % len(PROJECTS)]}。"
        elif kind == 2:
            predicate, value, sentence = "relationship_status_current", ("单身" if i % 8 else "已婚"), f"我目前的感情状态是{'单身' if i % 8 else '已婚'}。"
        else:
            predicate, value, sentence = "occupation_current", JOBS[(i + 1) % len(JOBS)], f"我现在从事{JOBS[(i + 1) % len(JOBS)]}工作。"
        turn = make_turn(sample_id, 1, when, sentence)
        rows.append(make_case(
            sample_id, "current_state", (i + 7) % 100, [turn],
            [fact(predicate, value, "current", "observed", 0.95, turn["turn_id"])],
            [expected_fact(predicate, value, "current")],
            expected_profile={predicate: value},
        ))

    for i in range(QUOTAS["historical_facts"]):
        sample_id = f"syn-{len(rows) + 1:04d}-history"
        when = anchor(i + 5)
        kind = i % 3
        if kind == 0:
            predicate, value, sentence = "current_location", CITIES[(i + 4) % len(CITIES)], f"我以前住在{CITIES[(i + 4) % len(CITIES)]}，后来搬走了。"
        elif kind == 1:
            predicate, value, sentence = "occupation_current", JOBS[(i + 2) % len(JOBS)], f"我上一份工作是{JOBS[(i + 2) % len(JOBS)]}，现在已经离职。"
        else:
            predicate, value, sentence = "current_project", PROJECTS[(i + 2) % len(PROJECTS)], f"我之前做过{PROJECTS[(i + 2) % len(PROJECTS)]}，项目已经结束。"
        turn = make_turn(sample_id, 1, when, sentence)
        rows.append(make_case(
            sample_id, "historical_facts", (i + 13) % 100, [turn],
            [fact(predicate, value, "historical", "observed", 0.94, turn["turn_id"])],
            [expected_fact(predicate, value, "historical")],
        ))

    for i in range(QUOTAS["state_conflicts"]):
        sample_id = f"syn-{len(rows) + 1:04d}-conflict"
        old_time = anchor(i + 1)
        new_time = old_time + timedelta(days=2)
        old_value = CITIES[i % len(CITIES)]
        new_value = CITIES[(i + 5) % len(CITIES)]
        while new_value == old_value:
            new_value = CITIES[(CITIES.index(new_value) + 1) % len(CITIES)]
        first = make_turn(sample_id, 1, old_time, f"我现在住在{old_value}。", 1)
        second = make_turn(sample_id, 2, new_time, f"我已经搬到{new_value}，现在住在这里。", 2)
        # Proposal order is intentionally newer-first, then older, to expose stale overwrite.
        proposal = [
            fact("current_location", new_value, "current", "observed", 0.97, second["turn_id"]),
            fact("current_location", old_value, "current", "observed", 0.95, first["turn_id"]),
        ]
        rows.append(make_case(
            sample_id, "state_conflicts", (i + 19) % 100, [first, second], proposal,
            [
                expected_fact("current_location", old_value, "current"),
                expected_fact("current_location", new_value, "current"),
            ],
            expected_profile={"current_location": new_value},
        ))

    for i in range(QUOTAS["explicit_time"]):
        sample_id = f"syn-{len(rows) + 1:04d}-explicit"
        when = anchor(i + 11)
        event = EVENTS[i % len(EVENTS)]
        relative = i % 2 == 1
        if relative:
            raw = list(RESOLVED_RELATIVE_TIMES)[(i // 2) % len(RESOLVED_RELATIVE_TIMES)]
            target_date = when.date() + timedelta(days=RESOLVED_RELATIVE_TIMES[raw])
            processing_time = when + timedelta(days=3)
            sentence = f"我{raw}要参加{event}。"
            case_variant = "resolved_relative_time"
        else:
            target_date = (when + timedelta(days=10 + (i % 50))).date()
            raw = target_date.isoformat() if i % 4 == 0 else f"{target_date.year}年{target_date.month}月{target_date.day}日"
            processing_time = when
            sentence = f"我计划在{raw}参加{event}。"
            case_variant = "absolute_time"
        turn = make_turn(sample_id, 1, when, sentence)
        rows.append(make_case(
            sample_id, "explicit_time", (i + 23) % 100, [turn],
            [fact("event", event, "episodic", "observed", 0.94, turn["turn_id"], raw_time=raw)],
            [expected_fact("event", event, "episodic")],
            expected_times=[{
                "source_turn_id": turn["turn_id"], "raw": raw,
                "status": "resolved", "normalized_start": target_date.isoformat(),
            }],
            processing_at=processing_time,
            case_variant=case_variant,
        ))

    for i in range(QUOTAS["ambiguous_time"]):
        sample_id = f"syn-{len(rows) + 1:04d}-ambiguous"
        when = anchor(i + 17)
        raw = AMBIGUOUS_TIMES[i % len(AMBIGUOUS_TIMES)]
        event = EVENTS[(i + 2) % len(EVENTS)]
        turn = make_turn(sample_id, 1, when, f"{raw}可能要参加{event}，时间还没定。")
        rows.append(make_case(
            sample_id, "ambiguous_time", (i + 29) % 100, [turn],
            [fact("event", event, "episodic", "possible", 0.78, turn["turn_id"], raw_time=raw)],
            [expected_fact("event", event, "episodic", "possible")],
            expected_times=[{
                "source_turn_id": turn["turn_id"], "raw": raw, "status": "ambiguous",
            }],
        ))

    for i in range(QUOTAS["plans_and_preferences"]):
        sample_id = f"syn-{len(rows) + 1:04d}-planpref"
        when = anchor(i + 21)
        if i % 2 == 0:
            value = CITIES[(i + 3) % len(CITIES)]
            turn = make_turn(sample_id, 1, when, f"我以后可能搬去{value}，目前还只是考虑。")
            proposal = fact("current_location", value, "planned", "possible", 0.76, turn["turn_id"], raw_time="以后")
            expected = [expected_fact("current_location", value, "planned", "possible")]
        else:
            value = PREFERENCES[(i // 2) % len(PREFERENCES)]
            turn = make_turn(sample_id, 1, when, f"我比较喜欢{value}。")
            proposal = fact("preference", value, "stable", "observed", 0.92, turn["turn_id"])
            expected = [expected_fact("preference", value, "stable")]
        rows.append(make_case(
            sample_id, "plans_and_preferences", (i + 31) % 100, [turn], [proposal], expected,
        ))

    for i in range(QUOTAS["negation_and_correction"]):
        sample_id = f"syn-{len(rows) + 1:04d}-negcorr"
        when = anchor(i + 25)
        if i % 2 == 0:
            value = CITIES[(i + 8) % len(CITIES)]
            turn = make_turn(sample_id, 1, when, f"我没有住在{value}，不要把它记成我的现居地。")
            proposal = fact("current_location", value, "current", "negated", 0.98, turn["turn_id"])
            rows.append(make_case(
                sample_id, "negation_and_correction", (i + 37) % 100, [turn], [proposal],
                [expected_fact("current_location", value, "current", "negated")],
            ))
        else:
            old_value = CITIES[i % len(CITIES)]
            new_value = CITIES[(i + 7) % len(CITIES)]
            old_time = when - timedelta(days=4)
            first = make_turn(sample_id, 1, old_time, f"我现在住在{old_value}。", 1)
            second = make_turn(sample_id, 2, when, f"更正一下，我现在住在{new_value}。", 2)
            rows.append(make_case(
                sample_id, "negation_and_correction", (i + 37) % 100, [first, second],
                [
                    fact("current_location", new_value, "current", "observed", 0.98, second["turn_id"]),
                    fact("current_location", old_value, "current", "observed", 0.94, first["turn_id"]),
                ],
                [
                    expected_fact("current_location", old_value, "current"),
                    expected_fact("current_location", new_value, "current"),
                ],
                expected_profile={"current_location": new_value},
            ))

    for i in range(QUOTAS["sensitive_interference"]):
        sample_id = f"syn-{len(rows) + 1:04d}-sensitive"
        when = anchor(i + 27)
        fake_secret = f"sk-FAKE-TEST-ONLY-DO-NOT-USE-{i + 1:06d}"
        turn = make_turn(
            sample_id, 1, when,
            f"合成安全测试文本，下面是假密钥，不是真实凭据：API_KEY={fake_secret}。不要保存。",
        )
        proposal = fact(
            "preference", fake_secret, "stable", "observed", 0.99, turn["turn_id"],
            raw_text=f"API_KEY={fake_secret}",
        )
        rows.append(make_case(
            sample_id, "sensitive_interference", (i + 41) % 100, [turn], [proposal], [], sensitive=True,
        ))

    for i in range(QUOTAS["cross_session"]):
        sample_id = f"syn-{len(rows) + 1:04d}-cross"
        first_time = anchor(i + 29)
        turn_count = 3 + (i % 3)
        is_conflict = i % 5 == 0
        old_value = CITIES[i % len(CITIES)]
        new_value = CITIES[(i + 6) % len(CITIES)]
        predicate = "current_location" if i % 2 == 0 else "home_location"
        value = old_value
        turns: list[dict[str, Any]] = []
        for n in range(turn_count):
            occurred = first_time + timedelta(days=7 * n)
            session = n + 1
            if n == 0:
                text = f"我{'现在住在' if predicate == 'current_location' else '长期居住在'}{old_value}。"
            elif is_conflict and n == turn_count - 1:
                text = f"更正近况，我已经搬到{new_value}。"
                value = new_value
            elif predicate == "home_location":
                text = f"补充一下，我长期居住的家仍在{old_value}。"
            else:
                text = f"补充一下，我目前还在{old_value}。"
            turns.append(make_turn(sample_id, n + 1, occurred, text, session))
        scope = "current" if predicate == "current_location" else "stable"
        expected = [expected_fact(predicate, old_value, scope)]
        expected_profile = {predicate: old_value}
        if is_conflict:
            expected = [
                expected_fact(predicate, old_value, scope),
                expected_fact(predicate, new_value, scope),
            ]
            expected_profile = {predicate: new_value}
            proposal = [
                fact(predicate, new_value, scope, "observed", 0.97, turns[-1]["turn_id"]),
                fact(predicate, old_value, scope, "observed", 0.95, turns[0]["turn_id"]),
            ]
        else:
            proposal = [
                fact(predicate, old_value, scope, "observed", 0.97, turns[-1]["turn_id"]),
                fact(predicate, old_value, scope, "observed", 0.95, turns[0]["turn_id"]),
            ]
        invalid_source_case = not is_conflict and i % 20 == 1
        if invalid_source_case:
            proposal.insert(1, fact(
                predicate, old_value, scope, "observed", 0.96,
                f"{sample_id}-missing-source",
            ))
        rows.append(make_case(
            sample_id, "cross_session", (i + 43) % 100, turns, proposal, expected,
            expected_profile=expected_profile,
            allow_invalid_sources=invalid_source_case,
            case_variant="invalid_source_reference" if invalid_source_case else ("cross_session_conflict" if is_conflict else "cross_session_repeat"),
        ))

    assert len(rows) == sum(QUOTAS.values()), (len(rows), sum(QUOTAS.values()))
    user_turn_totals = add_synthetic_user_histories(rows)
    for row in rows:
        valid_ids = {turn["turn_id"] for turn in row["turns"]}
        proposal_ids = {f["source_turn_id"] for f in row["proposal"]["facts"]}
        assert proposal_ids - valid_ids <= {f"{row['sample_id']}-missing-source"}, row["sample_id"]
    return rows


def main() -> int:
    rows = generate()
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    with DATA_PATH.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
    per_user_sample_counts = Counter(Counter(row["user_id"] for row in rows).values())
    per_user_turn_totals = Counter(
        sum(len(row["turns"]) for row in rows if row["user_id"] == user_id)
        for user_id in {row["user_id"] for row in rows}
    )
    manifest = {
        "generator": "generate_synthetic_curator_dataset.py",
        "generator_version": "1.2.0",
        "seed": 42,
        "synthetic_only": True,
        "sample_count": len(rows),
        "user_count": 100,
        "samples_per_user_distribution": dict(sorted(per_user_sample_counts.items())),
        "turns_per_user_distribution": dict(sorted(per_user_turn_totals.items())),
        "total_turns": sum(len(row["turns"]) for row in rows),
        "case_type_counts": dict(Counter(row["case_type"] for row in rows)),
        "case_variant_counts": dict(Counter(row.get("case_variant", "unspecified") for row in rows)),
        "invalid_source_reference_cases": sum(
            any(f["source_turn_id"] not in {t["turn_id"] for t in row["turns"]} for f in row["proposal"]["facts"])
            for row in rows
        ),
        "allowed_scopes": sorted(ALLOWED_SCOPES),
        "allowed_assertions": sorted(ALLOWED_ASSERTIONS),
        "time_anchor": "2026-09-20 through 2026-10-24 UTC; Asia/Shanghai turns",
        "notes": [
            "All user IDs, turns, names, locations, and credentials are synthetic placeholders.",
            "Fixture provider replays the provided proposal and measures the independent Python reference pipeline.",
            "Negated assertions are scored with their polarity and must not become positive current-profile values.",
            "Rows are evaluated independently; user_id is grouping metadata, not persistent state across rows.",
            "No invalid scope or assertion enum values are generated.",
            "occupation_current uses current scope; home_location uses stable scope.",
            "Expected fact matching includes predicate, value, scope, and assertion polarity.",
        ],
    }
    MANIFEST_PATH.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"dataset": str(DATA_PATH), "manifest": str(MANIFEST_PATH), **manifest}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
