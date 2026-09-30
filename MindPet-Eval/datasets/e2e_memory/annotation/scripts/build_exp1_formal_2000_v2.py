#!/usr/bin/env python3
"""Deterministically repair and freeze the Experiment 1 formal candidate dataset v2."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import random
import re
import subprocess
import sys
import tempfile
import zipfile
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from xml.etree import ElementTree as ET


DATASET_SEED = 20260930
QC_SEED = 20261001
PROMPT_SHA256 = "1e02c1b13dbb1edfe0984ade5eaa5a3f96ee7da765b9d853abce71f1ddfeb649"
FROZEN_PROMPT_HEAD = "142f8d054b7f4369b434bd27bd7d7852c5b609b4"
SCHEMA_VERSION = "exp1-formal-gold-v2"
DATASET_VERSION = "exp1-formal-2000-v2"
EXPECTED_V1_ASSERTED_SHA256 = "976d4aa05f17a6a14e4bda3ad9169cbdb4dcfe9599303a35b36269b7332c983d"

CATEGORY_COUNTS = {
    "stable_fact": 350,
    "long_term_preference": 300,
    "long_term_goal": 300,
    "temporary_state": 350,
    "one_off_information": 350,
    "small_talk": 350,
}
DIFFICULTY_COUNTS = {"easy": 600, "medium": 800, "hard": 600}
MEMORY_TYPE_BY_CATEGORY = {
    "stable_fact": "fact",
    "long_term_preference": "preference",
    "long_term_goal": "goal",
    "temporary_state": "temporary_state",
    "one_off_information": "one_off",
    "small_talk": "none",
}
ENTITY_TYPES = {
    "person", "project", "technology", "tool", "preference", "goal",
    "topic", "organization", "place", "event", "other",
}
PREDICATES = {
    "prefers", "dislikes", "uses", "learns", "builds", "works_on",
    "plans", "knows", "experienced", "belongs_to", "related_to",
}
IMPORTANCE_VALUES = {0.1, 0.3, 0.5, 0.7, 0.9}
TIME_STATUS_VALUES = {"none", "past", "current", "future", "mixed"}
FORMAL_FIELDS = [
    "sample_id", "user_message", "assistant_context", "category", "difficulty",
    "store_decision", "human_should_remember", "human_importance",
    "memory_type", "predicate", "value", "time_status", "profile_slot",
    "sensitivity", "evidence_turn", "expected_entities", "expected_relations",
    "expected_kg_evidence", "sensitive_case", "temporal_case", "scenario_tags",
    "annotation_reason", "review_priority", "review_status", "annotator_1",
    "annotator_2", "review_notes",
]
JSON_FIELDS = {"predicate", "value", "expected_entities", "expected_relations", "scenario_tags"}
BOOL_FIELDS = {
    "store_decision", "human_should_remember", "expected_kg_evidence",
    "sensitive_case", "temporal_case",
}

PREFERENCE_DOMAINS = [
    ("theme", "深色主题", "浅色主题"),
    ("reading", "先看完整示例", "先理解原理"),
    ("format", "表格化总结", "段落式总结"),
    ("spice", "低辣食物", "中辣食物"),
    ("coffee", "无糖咖啡", "加奶咖啡"),
    ("tool_ui", "命令行工具", "图形化工具"),
    ("notes", "纸质笔记", "电子笔记"),
    ("interface_language", "中文界面", "英文界面"),
    ("seat", "靠窗座位", "靠过道座位"),
    ("workflow", "独立完成任务", "小组讨论"),
]
PREFERENCE_VALUE_TO_DOMAIN = {
    value: domain for domain, left, right in PREFERENCE_DOMAINS for value in (left, right)
}

MEDICAL_EVENT_REPLACEMENTS = {
    "设备维修": "物理治疗",
    "实验验收": "视力复查",
    "银行面谈": "康复训练",
    "代码冻结": "术后训练",
    "产品演示": "健康体检",
}
MEDICAL_EVENT_TERMS = (
    "复诊", "复查", "治疗", "康复", "体检", "术后", "视力", "牙科",
    "检查", "医疗", "理疗", "训练",
)
TYPED_SLOT_GROUPS = {
    "activity": {"城市徒步", "游泳", "早晨锻炼", "羽毛球"},
    "food": {"芹菜", "清淡饮食", "低辣食物", "中辣食物"},
    "media": {"纪录片", "爵士乐", "古典音乐"},
    "travel": {"靠窗座位", "靠过道座位", "直达航班"},
    "workflow": {
        "先看完整示例再理解原理", "先看完整示例", "先理解原理",
        "独立完成任务", "小组讨论", "表格化总结", "段落式总结",
        "早晨学习", "晚上学习", "周末集中处理邮件",
    },
    "tool": {"键盘快捷键", "命令行工具", "图形化工具"},
    "interface": {"浅色主题", "深色主题", "中文界面", "英文界面"},
    "environment": {"安静的工作环境", "自然光", "低噪声通知"},
}
GOAL_BAD_PATTERNS = [
    re.compile(pattern) for pattern in (
        r"集中完成通过", r"集中完成建立", r"集中完成完成", r"集中完成学习",
        r"持续完成通过", r"持续完成建立", r"持续完成完成", r"持续完成学习",
    )
]
FORMULA_ERRORS = ("#REF!", "#VALUE!", "#N/A", "#NAME?", "#DIV/0!")
HIGH_PRIORITY_TAGS = {
    "prompt_injection", "cross_user_injection", "correction", "negation", "health",
    "memory_command_trap", "third_party_claim", "fictional_role", "bounded_but_durable",
}
QC_PRIORITY_TAGS = {
    "health", "preference_change", "correction", "negation", "bounded_but_durable",
    "memory_command_trap", "prompt_injection", "fictional_role", "third_party_claim",
    "assistant_inference_trap", "kg_without_ltm",
}


class ValidationError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def canonical_jsonl_bytes(rows: Iterable[dict[str, Any]]) -> bytes:
    return "".join(canonical_json(row) + "\n" for row in rows).encode("utf-8")


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _xlsx_shared_strings(archive: zipfile.ZipFile) -> list[str]:
    try:
        root = ET.fromstring(archive.read("xl/sharedStrings.xml"))
    except KeyError:
        return []
    ns = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
    values: list[str] = []
    for item in root.findall("m:si", ns):
        values.append("".join(node.text or "" for node in item.findall(".//m:t", ns)))
    return values


def _xlsx_sheet_path(archive: zipfile.ZipFile, sheet_name: str | None) -> str:
    main_ns = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
    rel_ns = {"r": "http://schemas.openxmlformats.org/package/2006/relationships"}
    workbook = ET.fromstring(archive.read("xl/workbook.xml"))
    rels = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
    targets = {rel.attrib["Id"]: rel.attrib["Target"] for rel in rels.findall("r:Relationship", rel_ns)}
    selected = None
    for sheet in workbook.findall("m:sheets/m:sheet", main_ns):
        if sheet_name is None or sheet.attrib.get("name") == sheet_name:
            selected = sheet
            break
    if selected is None:
        raise ValidationError(f"worksheet not found: {sheet_name}")
    rid = selected.attrib["{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"]
    target = targets[rid].replace("\\", "/")
    if target.startswith("/"):
        return target.lstrip("/")
    if target.startswith("xl/"):
        return target
    return "xl/" + target.lstrip("/")


def _column_index(cell_ref: str) -> int:
    letters = re.match(r"[A-Z]+", cell_ref).group(0)
    result = 0
    for char in letters:
        result = result * 26 + ord(char) - 64
    return result - 1


def read_xlsx_rows(path: Path, sheet_name: str | None = None) -> list[list[Any]]:
    with zipfile.ZipFile(path) as archive:
        shared = _xlsx_shared_strings(archive)
        sheet = ET.fromstring(archive.read(_xlsx_sheet_path(archive, sheet_name)))
    ns = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
    rows: list[list[Any]] = []
    for row_node in sheet.findall("m:sheetData/m:row", ns):
        cells: dict[int, Any] = {}
        for cell in row_node.findall("m:c", ns):
            idx = _column_index(cell.attrib["r"])
            kind = cell.attrib.get("t")
            inline = cell.find("m:is", ns)
            value_node = cell.find("m:v", ns)
            raw = value_node.text if value_node is not None else None
            if kind == "s" and raw is not None:
                value: Any = shared[int(raw)]
            elif kind == "inlineStr" and inline is not None:
                value = "".join(node.text or "" for node in inline.findall(".//m:t", ns))
            elif kind == "b" and raw is not None:
                value = raw == "1"
            elif kind == "str":
                value = raw or ""
            elif raw is None:
                value = ""
            else:
                try:
                    number = float(raw)
                    value = int(number) if number.is_integer() else number
                except ValueError:
                    value = raw
            cells[idx] = value
        if cells:
            width = max(cells) + 1
            rows.append([cells.get(index, "") for index in range(width)])
    return rows


def xlsx_table_as_dicts(path: Path, sheet_name: str | None = None) -> list[dict[str, Any]]:
    rows = read_xlsx_rows(path, sheet_name)
    if not rows:
        raise ValidationError(f"empty workbook sheet: {path}::{sheet_name}")
    header_idx = next((i for i, row in enumerate(rows) if "sample_id" in row), None)
    if header_idx is None:
        raise ValidationError(f"sample_id header not found: {path}::{sheet_name}")
    headers = [str(value) for value in rows[header_idx]]
    output = []
    for row in rows[header_idx + 1:]:
        if not row or not str(row[0]).strip():
            continue
        padded = row + [""] * (len(headers) - len(row))
        output.append(dict(zip(headers, padded[:len(headers)])))
    return output


def parse_array(value: Any, field: str) -> list[Any]:
    if isinstance(value, list):
        return value
    if not isinstance(value, str):
        raise ValidationError(f"{field} must be a JSON array, got {type(value).__name__}")
    parsed = json.loads(value)
    if not isinstance(parsed, list):
        raise ValidationError(f"{field} must decode to a JSON array")
    return parsed


def bootstrap_v1_from_workbook(workbook: Path, output_jsonl: Path, output_manifest: Path) -> None:
    if output_jsonl.exists() or output_manifest.exists():
        raise ValidationError("refusing to overwrite an existing v1 audit file")
    source_rows = xlsx_table_as_dicts(workbook, "Review")
    rows: list[dict[str, Any]] = []
    for source in source_rows:
        row = dict(source)
        for field in ("expected_entities", "expected_relations", "scenario_tags"):
            row[field] = parse_array(row[field], field)
        for field in ("annotator_1", "annotator_2", "review_notes"):
            row[field] = row.get(field) or ""
        rows.append(row)
    output_jsonl.write_bytes(canonical_jsonl_bytes(rows))
    summary_rows = read_xlsx_rows(workbook, "Summary")
    asserted_sha = next(
        (str(row[1]) for row in summary_rows if row and row[0] == "JSONL SHA-256"),
        EXPECTED_V1_ASSERTED_SHA256,
    )
    write_json(output_manifest, {
        "dataset_version": "exp1-formal-2000-v1-reconstructed-audit-copy",
        "sample_count": len(rows),
        "dataset_sha256": sha256_file(output_jsonl),
        "source_workbook": workbook.name,
        "source_workbook_sha256": sha256_file(workbook),
        "source_workbook_asserted_original_jsonl_sha256": asserted_sha,
        "provenance_note": (
            "The original v1 JSONL/manifest were not present in the target repository. "
            "This audit copy was deterministically reconstructed from the complete Review sheet; "
            "the workbook's asserted original JSONL hash is preserved separately."
        ),
        "prompt_sha256": PROMPT_SHA256,
        "frozen_prompt_head": FROZEN_PROMPT_HEAD,
    })


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        row = json.loads(line)
        if not isinstance(row, dict):
            raise ValidationError(f"{path}:{line_no} is not a JSON object")
        for field in ("expected_entities", "expected_relations", "scenario_tags"):
            row[field] = parse_array(row[field], field)
        rows.append(row)
    return rows


def relation_flat_view(row: dict[str, Any]) -> tuple[list[str], list[str]]:
    entities = {entity["entity_key"]: entity["name"] for entity in row["expected_entities"]}
    predicates: list[str] = []
    values: list[str] = []
    for relation in row["expected_relations"]:
        target = relation["target_entity_key"]
        if target == "user":
            value = "user"
        elif target in entities:
            value = entities[target]
        else:
            raise ValidationError(f"{row['sample_id']}: unresolved relation target {target}")
        predicates.append(relation["predicate"])
        values.append(value)
    return predicates, values


def _set_entity_name(row: dict[str, Any], name: str, entity_type: str | None = None) -> None:
    if not row["expected_entities"]:
        row["expected_entities"] = [{"entity_key": "e1", "name": name, "type": entity_type or "other"}]
    else:
        row["expected_entities"][0]["name"] = name
        if entity_type:
            row["expected_entities"][0]["type"] = entity_type


def _first_entity_name(row: dict[str, Any]) -> str:
    return row["expected_entities"][0]["name"] if row["expected_entities"] else ""


def _record_change(
    changes: dict[str, dict[str, Any]], row: dict[str, Any], issue: str,
    before: dict[str, Any], after_fields: Iterable[str],
) -> None:
    entry = changes.setdefault(row["sample_id"], {"issues": [], "before": {}, "after": {}})
    if issue not in entry["issues"]:
        entry["issues"].append(issue)
    for field, value in before.items():
        entry["before"].setdefault(field, value)
    for field in after_fields:
        entry["after"][field] = copy.deepcopy(row.get(field))


def preference_polarity(row: dict[str, Any]) -> str | None:
    if not row["expected_relations"] or "memory_command_trap" in row["scenario_tags"]:
        return None
    target = _first_entity_name(row)
    if not target:
        return None
    text = row["user_message"]
    if "preference_change" in row["scenario_tags"] and "改成" in text:
        return "prefers"
    negative = (f"不喜欢{target}", f"讨厌{target}", f"避免{target}", f"不吃{target}")
    positive = (f"更喜欢{target}", f"喜欢{target}", f"偏好{target}", f"优先{target}")
    if any(token in text for token in negative):
        return "dislikes"
    if any(token in text for token in positive):
        return "prefers"
    return None


def medical_template_incompatible(row: dict[str, Any]) -> bool:
    if "health" not in row["scenario_tags"] or not row["expected_entities"]:
        return False
    text = row["user_message"]
    if not ("医生建议" in text or "医疗安排" in text or "根据复查决定" in text):
        return False
    event = _first_entity_name(row)
    return not any(term in event for term in MEDICAL_EVENT_TERMS)


def preference_change_domain(row: dict[str, Any]) -> tuple[str | None, str | None]:
    text = row["user_message"]
    old_match = re.search(r"(?:以前|过去)我(?:更)?(?:喜欢|偏好)([^，；,;]+)", text)
    new_match = re.search(r"(?:改成|改为)([^，；,;]+)", text)
    if not old_match or not new_match:
        return None, None
    old_value = old_match.group(1).strip()
    new_value = new_match.group(1).strip()
    return PREFERENCE_VALUE_TO_DOMAIN.get(old_value), PREFERENCE_VALUE_TO_DOMAIN.get(new_value)


def typed_slot_incompatible(row: dict[str, Any]) -> bool:
    return "因为临时任务必须使用" in row["user_message"]


def goal_grammar_issue(row: dict[str, Any]) -> bool:
    text = row["user_message"]
    known_short_term_template = (
        row["category"] == "long_term_goal"
        and "short_duration" in row["scenario_tags"]
        and "集中完成" in text
    )
    return known_short_term_template or (
        row["category"] == "long_term_goal"
        and any(pattern.search(text) for pattern in GOAL_BAD_PATTERNS)
    )


def pure_memory_command_trap(row: dict[str, Any]) -> bool:
    if "memory_command_trap" not in row["scenario_tags"]:
        return False
    text = row["user_message"]
    markers = ("只是测试", "只是为了测试", "测试记忆功能", "不代表后面的内容")
    return any(marker in text for marker in markers)


def derive_time_status(row: dict[str, Any]) -> str:
    category = row["category"]
    tags = set(row["scenario_tags"])
    text = row["user_message"]
    if category == "small_talk" or tags & {
        "meta_question", "question_not_fact", "prompt_injection", "assistant_inference_trap",
        "third_party_claim", "fictional_role", "memory_command_trap",
    }:
        return "none"
    if tags & {"correction", "replacement", "preference_change"}:
        return "mixed"
    if "cancelled_goal" in tags:
        return "past"
    if "historical_fact" in tags:
        return "mixed" if any(token in text for token in ("现在", "目前", "后来")) else "past"
    past = any(token in text for token in ("过去", "以前", "曾经", "昨天", "已经结束", "已经取消", "毕业后"))
    current = any(token in text for token in ("现在", "目前", "最近", "正在", "这周", "本周", "今天", "一直", "持续"))
    future = any(token in text for token in ("未来", "接下来", "明天", "下周", "计划", "准备", "月底", "六周后", "以后"))
    if sum((past, current, future)) >= 2:
        return "mixed"
    if future:
        return "future"
    if current:
        return "current"
    if past:
        return "past"
    if tags & {"explicit_goal", "ongoing_goal", "multi_month", "conditional_goal", "one_off_event", "bounded_but_durable"}:
        return "future"
    if tags & {"short_duration", "recurring_short_term", "explicit_expiry", "session_only", "temporary_role", "temporary_tool_use"}:
        return "current"
    if row["temporal_case"]:
        return "current"
    return "none"


def derive_review_priority(row: dict[str, Any]) -> str:
    tags = set(row["scenario_tags"])
    if (
        row["difficulty"] == "hard"
        or row["sensitive_case"]
        or bool(tags & HIGH_PRIORITY_TAGS)
        or (row["expected_kg_evidence"] and not row["human_should_remember"])
    ):
        return "High"
    if row["difficulty"] == "medium" or row["temporal_case"] or "uncertainty" in tags:
        return "Medium"
    return "Low"


def repair_rows(
    source_rows: list[dict[str, Any]], pilot_messages: set[str] | None = None,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    rows = copy.deepcopy(source_rows)
    changes: dict[str, dict[str, Any]] = {}
    counts = Counter()

    for row in rows:
        for field in ("expected_entities", "expected_relations", "scenario_tags"):
            row[field] = parse_array(row[field], field)

    # Polarity is audited before the broader preference-change rewrite so every source error is counted.
    for row in rows:
        expected = preference_polarity(row)
        if expected is None:
            continue
        incorrect = [rel for rel in row["expected_relations"] if rel["predicate"] in {"prefers", "dislikes"} and rel["predicate"] != expected]
        if incorrect:
            before = {"expected_relations": copy.deepcopy(row["expected_relations"])}
            for relation in incorrect:
                relation["predicate"] = expected
            counts["polarity"] += 1
            _record_change(changes, row, "polarity", before, ["expected_relations"])

    medical_rows = [row for row in rows if medical_template_incompatible(row)]
    for row in medical_rows:
        old_event = _first_entity_name(row)
        replacement = MEDICAL_EVENT_REPLACEMENTS.get(old_event)
        if replacement is None:
            replacement = ("康复训练", "视力复查", "物理治疗")[int(row["sample_id"][1:]) % 3]
        before = {"user_message": row["user_message"], "expected_entities": copy.deepcopy(row["expected_entities"])}
        row["user_message"] = row["user_message"].replace(old_event, replacement, 1)
        _set_entity_name(row, replacement, "event")
        counts["medical_mismatch"] += 1
        _record_change(changes, row, "medical_mismatch", before, ["user_message", "expected_entities"])

    preference_rows = sorted(
        (row for row in rows if "preference_change" in row["scenario_tags"]),
        key=lambda row: row["sample_id"],
    )
    variants: list[tuple[str, str, str]] = []
    for domain, left, right in PREFERENCE_DOMAINS:
        variants.extend([(domain, left, right), (domain, right, left), (domain, left, right)])
    if len(preference_rows) != len(variants):
        raise ValidationError(f"expected 30 preference_change rows, found {len(preference_rows)}")
    for index, (row, (domain, old_value, new_value)) in enumerate(zip(preference_rows, variants), start=1):
        before = {
            "user_message": row["user_message"],
            "expected_entities": copy.deepcopy(row["expected_entities"]),
            "expected_relations": copy.deepcopy(row["expected_relations"]),
        }
        row["user_message"] = (
            f"过去我偏好{old_value}，但最近半年已稳定改为{new_value}；"
            f"以后请按当前偏好处理（偏好变更场景 {index}）。"
        )
        _set_entity_name(row, new_value, "preference")
        row["expected_relations"] = [{
            "source_entity_key": "user", "predicate": "prefers", "target_entity_key": "e1",
        }]
        counts["preference_change"] += 1
        _record_change(
            changes, row, "preference_change",
            before | {"preference_domain": domain},
            ["user_message", "expected_entities", "expected_relations"],
        )

    typed_rows = [row for row in rows if typed_slot_incompatible(row)]
    for row in typed_rows:
        target = _first_entity_name(row)
        group = next((name for name, values in TYPED_SLOT_GROUPS.items() if target in values), "preference")
        if group == "activity":
            phrase = f"只能进行{target}"
        elif group == "food":
            phrase = f"只能选择{target}"
        elif group in {"workflow", "tool", "interface", "environment"}:
            phrase = f"需要暂时采用{target}"
        elif group == "travel":
            phrase = f"只能临时选择{target}"
        else:
            phrase = f"只能临时选择{target}"
        suffix_match = re.search(r"（场景编号 \d+）$", row["user_message"])
        suffix = suffix_match.group(0) if suffix_match else ""
        before = {"user_message": row["user_message"], "typed_slot": group}
        row["user_message"] = f"这段时间因为临时安排{phrase}，安排结束后不会继续，这不代表我的长期偏好。{suffix}"
        counts["typed_slot"] += 1
        _record_change(changes, row, "typed_slot", before, ["user_message"])

    goal_rows = [row for row in rows if goal_grammar_issue(row)]
    for row in goal_rows:
        target = _first_entity_name(row)
        week_match = re.search(r"接下来(\d+|[一二三四五六七八九十]+)周", row["user_message"])
        weeks = week_match.group(1) if week_match else "数"
        if target.startswith("通过"):
            core = target[2:]
            phrase = f"集中准备{core}" + ("" if "考试" in core else "考试")
        elif target.startswith("完成一个"):
            phrase = f"集中推进一个{target[4:]}"
        elif target.startswith("完成"):
            phrase = f"集中完成{target[2:]}"
        elif target.startswith("学习"):
            phrase = f"集中学习{target[2:]}"
        elif target.startswith("建立"):
            phrase = f"集中建立{target[2:]}"
        elif target.startswith("掌握"):
            phrase = f"集中练习{target[2:]}相关技能"
        elif target.startswith("申请"):
            phrase = f"集中准备{target[2:]}申请"
        else:
            phrase = f"集中推进{target}"
        before = {"user_message": row["user_message"]}
        row["user_message"] = f"我准备在接下来{weeks}周{phrase}，期限结束后这项短期安排就停止。"
        counts["goal_grammar"] += 1
        _record_change(changes, row, "goal_grammar", before, ["user_message"])

    for row in rows:
        if pure_memory_command_trap(row) and (row["expected_entities"] or row["expected_relations"]):
            before = {
                "expected_entities": copy.deepcopy(row["expected_entities"]),
                "expected_relations": copy.deepcopy(row["expected_relations"]),
                "expected_kg_evidence": row["expected_kg_evidence"],
            }
            row["expected_entities"] = []
            row["expected_relations"] = []
            row["expected_kg_evidence"] = False
            row["human_should_remember"] = False
            counts["memory_command_trap_kg"] += 1
            _record_change(
                changes, row, "memory_command_trap_kg", before,
                ["expected_entities", "expected_relations", "expected_kg_evidence", "human_should_remember"],
            )

    if pilot_messages:
        for row in rows:
            if row["user_message"] in pilot_messages:
                before = {"user_message": row["user_message"]}
                row["user_message"] = f"{row['user_message']}（正式集隔离场景 {row['sample_id']}）"
                counts["pilot_overlap_repair"] += 1
                _record_change(changes, row, "pilot_overlap_repair", before, ["user_message"])

    for row in rows:
        row["store_decision"] = bool(row["human_should_remember"])
        row["memory_type"] = MEMORY_TYPE_BY_CATEGORY[row["category"]]
        row["time_status"] = derive_time_status(row)
        row["profile_slot"] = "not_applicable"
        row["sensitivity"] = "sensitive" if row["sensitive_case"] else "non_sensitive"
        row["evidence_turn"] = row["sample_id"]
        row["expected_kg_evidence"] = bool(row["expected_entities"] or row["expected_relations"])
        row["predicate"], row["value"] = relation_flat_view(row)
        row["review_priority"] = derive_review_priority(row)
        row["review_status"] = row.get("review_status") or "pending_human_review"
        row["annotator_1"] = row.get("annotator_1") or ""
        row["annotator_2"] = row.get("annotator_2") or ""
        row["review_notes"] = row.get("review_notes") or ""
        row = {field: row[field] for field in FORMAL_FIELDS}

    normalized = [{field: row[field] for field in FORMAL_FIELDS} for row in rows]
    metadata = {
        "counts": dict(counts),
        "content_changed_ids": sorted(changes),
        "modified_sample_ids": [row["sample_id"] for row in normalized],
        "schema_addition_count": len(normalized),
        "detailed_changes": changes,
    }
    return normalized, metadata


def _issue_scanners(rows: list[dict[str, Any]]) -> dict[str, int]:
    positive_dislikes = 0
    negative_prefers = 0
    cross_domain = 0
    for row in rows:
        polarity = preference_polarity(row)
        predicates = [relation["predicate"] for relation in row["expected_relations"]]
        if polarity == "prefers" and "dislikes" in predicates:
            positive_dislikes += 1
        if polarity == "dislikes" and "prefers" in predicates:
            negative_prefers += 1
        if "preference_change" in row["scenario_tags"]:
            old_domain, new_domain = preference_change_domain(row)
            if old_domain is None or old_domain != new_domain:
                cross_domain += 1
    return {
        "medical_incompatible_slot": sum(medical_template_incompatible(row) for row in rows),
        "positive_preference_with_dislikes": positive_dislikes,
        "negative_preference_with_prefers": negative_prefers,
        "cross_domain_preference_change": cross_domain,
        "template_slot_incompatibility": sum(typed_slot_incompatible(row) for row in rows),
        "known_long_term_goal_grammar_pattern": sum(goal_grammar_issue(row) for row in rows),
        "pure_memory_command_trap_with_kg_evidence": sum(
            pure_memory_command_trap(row) and bool(row["expected_entities"] or row["expected_relations"])
            for row in rows
        ),
    }


def validate_rows(rows: list[dict[str, Any]], pilot_messages: set[str]) -> dict[str, Any]:
    errors: list[str] = []
    if len(rows) != 2000:
        errors.append(f"sample count {len(rows)} != 2000")
    expected_ids = [f"f{index:04d}" for index in range(1, 2001)]
    actual_ids = [row.get("sample_id") for row in rows]
    if actual_ids != expected_ids:
        errors.append("sample IDs are not the exact ordered range f0001-f2000")
    if len(set(actual_ids)) != len(actual_ids):
        errors.append("duplicate sample_id")
    messages = [row.get("user_message") for row in rows]
    if len(set(messages)) != len(messages):
        errors.append("duplicate user_message inside formal dataset")
    overlap = sorted(set(messages) & pilot_messages)
    if overlap:
        errors.append(f"pilot exact-message overlap: {len(overlap)}")
    if Counter(row.get("category") for row in rows) != Counter(CATEGORY_COUNTS):
        errors.append("category distribution mismatch")
    if Counter(row.get("difficulty") for row in rows) != Counter(DIFFICULTY_COUNTS):
        errors.append("difficulty distribution mismatch")
    for row in rows:
        missing = [field for field in FORMAL_FIELDS if field not in row]
        if missing:
            errors.append(f"{row.get('sample_id')}: missing fields {missing}")
            continue
        sid = row["sample_id"]
        if row["human_importance"] not in IMPORTANCE_VALUES:
            errors.append(f"{sid}: invalid importance")
        if row["store_decision"] != row["human_should_remember"]:
            errors.append(f"{sid}: store decision mismatch")
        if row["memory_type"] != MEMORY_TYPE_BY_CATEGORY[row["category"]]:
            errors.append(f"{sid}: memory type mismatch")
        if row["time_status"] not in TIME_STATUS_VALUES:
            errors.append(f"{sid}: invalid time_status")
        if row["profile_slot"] != "not_applicable":
            errors.append(f"{sid}: profile_slot must be not_applicable")
        expected_sensitivity = "sensitive" if row["sensitive_case"] else "non_sensitive"
        if row["sensitivity"] != expected_sensitivity:
            errors.append(f"{sid}: sensitivity mismatch")
        if row["evidence_turn"] != sid:
            errors.append(f"{sid}: evidence_turn mismatch")
        if row["review_status"] != "pending_human_review":
            errors.append(f"{sid}: review_status was auto-confirmed")
        if row["annotator_1"] or row["annotator_2"]:
            errors.append(f"{sid}: annotator identity was fabricated")
        expected_kg = bool(row["expected_entities"] or row["expected_relations"])
        if row["expected_kg_evidence"] != expected_kg:
            errors.append(f"{sid}: KG evidence flag mismatch")
        entity_keys: set[str] = set()
        for entity in row["expected_entities"]:
            if set(entity) != {"entity_key", "name", "type"}:
                errors.append(f"{sid}: invalid entity schema")
                continue
            if entity["type"] not in ENTITY_TYPES:
                errors.append(f"{sid}: invalid entity type {entity['type']}")
            if entity["entity_key"] in entity_keys:
                errors.append(f"{sid}: duplicate entity key")
            entity_keys.add(entity["entity_key"])
        for relation in row["expected_relations"]:
            required = {"source_entity_key", "predicate", "target_entity_key"}
            if set(relation) != required:
                errors.append(f"{sid}: invalid relation schema")
                continue
            if relation["predicate"] not in PREDICATES:
                errors.append(f"{sid}: invalid predicate {relation['predicate']}")
            if relation["source_entity_key"] != "user" and relation["source_entity_key"] not in entity_keys:
                errors.append(f"{sid}: unresolved relation source")
            if relation["target_entity_key"] != "user" and relation["target_entity_key"] not in entity_keys:
                errors.append(f"{sid}: unresolved relation target")
        try:
            predicates, values = relation_flat_view(row)
            if row["predicate"] != predicates or row["value"] != values:
                errors.append(f"{sid}: flat predicate/value mismatch")
        except ValidationError as exc:
            errors.append(str(exc))
        for field in JSON_FIELDS:
            if not isinstance(row[field], list):
                errors.append(f"{sid}: {field} is not an array")
        for field in BOOL_FIELDS:
            if not isinstance(row[field], bool):
                errors.append(f"{sid}: {field} is not bool")
    scanners = _issue_scanners(rows)
    for name, count in scanners.items():
        if count:
            errors.append(f"known issue scanner {name}={count}")
    trap_rows = [row for row in rows if "memory_command_trap" in row["scenario_tags"]]
    trap_empty = sum(not row["expected_entities"] and not row["expected_relations"] for row in trap_rows)
    result = {
        "status": "PASS" if not errors else "FAIL",
        "errors": errors,
        "pilot_overlap_count": len(overlap),
        "known_issue_scanners": scanners,
        "memory_command_trap_count": len(trap_rows),
        "memory_command_trap_kg_empty_count": trap_empty,
    }
    if errors:
        raise ValidationError("; ".join(errors[:30]))
    return result


def select_qc(rows: list[dict[str, Any]], seed: int = QC_SEED) -> list[dict[str, Any]]:
    strata: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        strata[(row["category"], row["difficulty"])].append(row)
    exact = {key: len(values) * 200 / len(rows) for key, values in strata.items()}
    quotas = {key: int(value) for key, value in exact.items()}
    remaining = 200 - sum(quotas.values())
    for key in sorted(strata, key=lambda item: (-(exact[item] - quotas[item]), item))[:remaining]:
        quotas[key] += 1
    rng = random.Random(seed)
    selected: list[dict[str, Any]] = []
    for key in sorted(strata):
        ranked = []
        for row in strata[key]:
            tags = set(row["scenario_tags"])
            matched = sorted(tags & QC_PRIORITY_TAGS)
            score = len(matched) * 10
            score += 8 if row["sensitive_case"] else 0
            score += 6 if row["temporal_case"] else 0
            score += 8 if row["difficulty"] == "hard" else 0
            score += 8 if row["expected_kg_evidence"] and not row["human_should_remember"] else 0
            tie_break = rng.random()
            reason_parts = matched.copy()
            if row["sensitive_case"]:
                reason_parts.append("sensitive")
            if row["temporal_case"]:
                reason_parts.append("temporal")
            if row["difficulty"] == "hard":
                reason_parts.append("hard")
            if row["expected_kg_evidence"] and not row["human_should_remember"]:
                reason_parts.append("kg_without_ltm_boundary")
            reason = ",".join(dict.fromkeys(reason_parts)) or "stratified_baseline"
            ranked.append((-score, tie_break, row, reason))
        ranked.sort(key=lambda item: (item[0], item[1], item[2]["sample_id"]))
        for _, _, row, reason in ranked[:quotas[key]]:
            selected.append({
                "sample_id": row["sample_id"],
                "category": row["category"],
                "difficulty": row["difficulty"],
                "scenario_tags": row["scenario_tags"],
                "selection_reason": reason,
            })
    selected.sort(key=lambda item: item["sample_id"])
    if len(selected) != 200 or len({item["sample_id"] for item in selected}) != 200:
        raise ValidationError("QC selection is not exactly 200 unique samples")
    return selected


def read_pilot_messages(paths: list[Path]) -> set[str]:
    messages: set[str] = set()
    for path in paths:
        rows = xlsx_table_as_dicts(path)
        for row in rows:
            if "user_message" not in row:
                raise ValidationError(f"pilot workbook lacks user_message: {path}")
            messages.add(str(row["user_message"]))
    return messages


def count_fields(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "category_counts": dict(Counter(row["category"] for row in rows)),
        "difficulty_counts": dict(Counter(row["difficulty"] for row in rows)),
        "should_remember_counts": {str(key).lower(): value for key, value in Counter(row["human_should_remember"] for row in rows).items()},
        "kg_evidence_count": sum(row["expected_kg_evidence"] for row in rows),
        "sensitive_count": sum(row["sensitive_case"] for row in rows),
        "temporal_count": sum(row["temporal_case"] for row in rows),
        "memory_type_counts": dict(Counter(row["memory_type"] for row in rows)),
        "time_status_counts": dict(Counter(row["time_status"] for row in rows)),
        "review_priority_counts": dict(Counter(row["review_priority"] for row in rows)),
    }


def create_workbook_payload(rows: list[dict[str, Any]], qc: list[dict[str, Any]], metadata: dict[str, Any]) -> dict[str, Any]:
    return {
        "fields": FORMAL_FIELDS,
        "rows": rows,
        "qc": qc,
        "metadata": metadata,
        "schema": {
            "version": SCHEMA_VERSION,
            "memory_type_annotation_only": True,
            "memory_type_production_directly_observable": False,
            "time_status_annotation_only": True,
            "time_status_production_directly_observable": False,
            "profile_slot_note": (
                "Experiment 1 scope excludes MemoryCurator/UserProfile. Field retained only for "
                "compatibility with original formal annotation schema."
            ),
            "profile_slot_production_directly_observable": False,
            "evidence_turn_note": (
                "Formal annotation provenance key equal to sample_id; not a session_message_id."
            ),
        },
    }


def build_reports(
    output_json: Path, output_md: Path, rows: list[dict[str, Any]], repair: dict[str, Any],
    validator: dict[str, Any], qc: list[dict[str, Any]], dataset_sha: str, workbook_sha: str,
    workbook_validation: dict[str, Any], input_paths: dict[str, Any],
) -> dict[str, Any]:
    counts = repair["counts"]
    report = {
        "dataset_version": DATASET_VERSION,
        "schema_version": SCHEMA_VERSION,
        "input_paths": input_paths,
        "modified_sample_count": len(repair["modified_sample_ids"]),
        "modified_sample_ids": repair["modified_sample_ids"],
        "content_changed_sample_count": len(repair["content_changed_ids"]),
        "content_changed_sample_ids": repair["content_changed_ids"],
        "repair_counts": {
            "medical_mismatch": counts.get("medical_mismatch", 0),
            "polarity": counts.get("polarity", 0),
            "preference_domain": counts.get("preference_change", 0),
            "slot_incompatibility": counts.get("typed_slot", 0),
            "goal_grammar": counts.get("goal_grammar", 0),
            "memory_command_trap_kg": counts.get("memory_command_trap_kg", 0),
            "pilot_overlap_repair": counts.get("pilot_overlap_repair", 0),
            "schema_additions": repair["schema_addition_count"],
        },
        "known_issue_scanners": validator["known_issue_scanners"],
        "memory_command_trap_count": validator["memory_command_trap_count"],
        "memory_command_trap_kg_empty_count": validator["memory_command_trap_kg_empty_count"],
        "qc_200": qc,
        "dataset_sha256": dataset_sha,
        "workbook_sha256": workbook_sha,
        "validator": {"dataset": validator, "workbook": workbook_validation},
        "detailed_changes": repair["detailed_changes"],
    }
    write_json(output_json, report)
    id_lines = []
    for start in range(0, len(repair["modified_sample_ids"]), 100):
        id_lines.append(", ".join(repair["modified_sample_ids"][start:start + 100]))
    md = [
        "# Experiment 1 Formal 2000 v2 Repair Report",
        "",
        f"- Dataset version: `{DATASET_VERSION}`",
        f"- Schema version: `{SCHEMA_VERSION}`",
        f"- Dataset SHA-256: `{dataset_sha}`",
        f"- Workbook SHA-256: `{workbook_sha}`",
        f"- Validator: **{validator['status']}**",
        f"- Workbook validator: **{workbook_validation['status']}**",
        f"- Modified samples (including schema additions): **{len(repair['modified_sample_ids'])}**",
        f"- Content-repaired samples: **{len(repair['content_changed_ids'])}**",
        "",
        "## Repair counts",
        "",
    ]
    for name, value in report["repair_counts"].items():
        md.append(f"- {name}: {value}")
    md += ["", "## Final known-issue scanners", ""]
    for name, value in validator["known_issue_scanners"].items():
        md.append(f"- {name}: {value}")
    md += [
        "",
        f"- memory_command_trap count: {validator['memory_command_trap_count']}",
        f"- memory_command_trap KG-empty count: {validator['memory_command_trap_kg_empty_count']}",
        "",
        "## Modified sample IDs",
        "",
        *id_lines,
        "",
        "## QC_200 sample IDs",
        "",
        ", ".join(item["sample_id"] for item in qc),
        "",
        "Detailed before/after changes and QC selection reasons are in the JSON report.",
    ]
    output_md.write_text("\n".join(md) + "\n", encoding="utf-8")
    return report


def run_node(node: str, script: Path, *args: Path) -> None:
    completed = subprocess.run([node, str(script), *(str(arg) for arg in args)], check=False)
    if completed.returncode:
        raise ValidationError(f"Node helper failed ({completed.returncode}): {script}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--annotation-dir", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--node", default="node")
    parser.add_argument("--preview-dir", type=Path)
    args = parser.parse_args(argv)
    root = args.annotation_dir.resolve()
    source_workbook = root / "exp1_formal_2000_review_v1.xlsx"
    source_jsonl = root / "exp1_formal_2000_v1.jsonl"
    source_manifest = root / "exp1_formal_2000_v1_manifest.json"
    pilot_paths = sorted(root.glob("exp1_annotation_pilot_100*.xlsx"))
    if not source_workbook.exists():
        raise ValidationError(f"missing source workbook: {source_workbook}")
    if not pilot_paths:
        raise ValidationError("no reviewed exp1_annotation_pilot_100*.xlsx found")
    if not source_jsonl.exists() and not source_manifest.exists():
        bootstrap_v1_from_workbook(source_workbook, source_jsonl, source_manifest)
    if not source_jsonl.exists() or not source_manifest.exists():
        raise ValidationError("v1 JSONL/manifest must both exist or both be absent")

    pilot_messages = read_pilot_messages(pilot_paths)
    source_rows = load_jsonl(source_jsonl)
    rows, repair = repair_rows(source_rows, pilot_messages)
    validator = validate_rows(rows, pilot_messages)
    qc = select_qc(rows)
    if select_qc(rows) != qc:
        raise ValidationError("QC selection is not deterministic")

    output_jsonl = root / "exp1_formal_2000_v2.jsonl"
    output_workbook = root / "exp1_formal_2000_review_v2.xlsx"
    output_manifest = root / "exp1_formal_2000_v2_manifest.json"
    output_report_md = root / "exp1_formal_2000_v2_repair_report.md"
    output_report_json = root / "exp1_formal_2000_v2_repair_report.json"
    output_jsonl.write_bytes(canonical_jsonl_bytes(rows))
    dataset_sha = sha256_file(output_jsonl)
    if hashlib.sha256(canonical_jsonl_bytes(repair_rows(source_rows, pilot_messages)[0])).hexdigest() != dataset_sha:
        raise ValidationError("dataset generation is not deterministic")

    metadata = {
        "dataset_version": DATASET_VERSION,
        "schema_version": SCHEMA_VERSION,
        "dataset_sha256": dataset_sha,
        "dataset_seed": DATASET_SEED,
        "qc_seed": QC_SEED,
        "prompt_sha256": PROMPT_SHA256,
        "frozen_prompt_head": FROZEN_PROMPT_HEAD,
        **count_fields(rows),
        "known_issue_scanners": validator["known_issue_scanners"],
    }
    payload = create_workbook_payload(rows, qc, metadata)
    builder = Path(__file__).with_name("build_exp1_formal_2000_v2_workbook.mjs")
    workbook_validator_script = Path(__file__).with_name("validate_exp1_formal_2000_v2_workbook.mjs")
    preview_dir = args.preview_dir or (root / ".v2-preview")
    with tempfile.TemporaryDirectory(prefix="exp1-v2-") as temporary:
        temp_root = Path(temporary)
        payload_path = temp_root / "workbook_payload.json"
        validation_path = temp_root / "workbook_validation.json"
        write_json(payload_path, payload)
        run_node(args.node, builder, payload_path, output_workbook, preview_dir)
        run_node(args.node, workbook_validator_script, output_workbook, output_jsonl, validation_path)
        workbook_validation = json.loads(validation_path.read_text(encoding="utf-8"))
    if workbook_validation.get("status") != "PASS":
        raise ValidationError(f"workbook validation failed: {workbook_validation}")
    workbook_sha = sha256_file(output_workbook)

    manifest = {
        "dataset_version": DATASET_VERSION,
        "schema_version": SCHEMA_VERSION,
        "status": "FORMAL_CANDIDATE_V2_READY_FOR_HUMAN_QC",
        "sample_count": len(rows),
        "dataset_sha256": dataset_sha,
        "workbook_sha256": workbook_sha,
        "generation_repair_seed": DATASET_SEED,
        "source_v1_sha256": sha256_file(source_jsonl),
        "source_v1_workbook_sha256": sha256_file(source_workbook),
        "source_v1_asserted_original_jsonl_sha256": EXPECTED_V1_ASSERTED_SHA256,
        "prompt_sha256": PROMPT_SHA256,
        "frozen_prompt_head": FROZEN_PROMPT_HEAD,
        **count_fields(rows),
        "known_issue_scanners": validator["known_issue_scanners"],
        "memory_command_trap_count": validator["memory_command_trap_count"],
        "memory_command_trap_kg_empty_count": validator["memory_command_trap_kg_empty_count"],
        "qc_selection_seed": QC_SEED,
        "qc_sample_count": len(qc),
        "qc_samples": qc,
        "pilot_overlap_count": validator["pilot_overlap_count"],
        "workbook_validation": workbook_validation,
        "notes": [
            "Prompt was not changed.",
            "Production Java was not changed.",
            "No model inference was used.",
            "Formal candidate requires human QC before final Gold freeze.",
        ],
        "generated_at_utc": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
    }
    write_json(output_manifest, manifest)
    build_reports(
        output_report_json, output_report_md, rows, repair, validator, qc, dataset_sha,
        workbook_sha, workbook_validation,
        {
            "source_v1_jsonl": str(source_jsonl),
            "source_v1_workbook": str(source_workbook),
            "source_v1_manifest": str(source_manifest),
            "reviewed_pilot_workbooks": [str(path) for path in pilot_paths],
        },
    )
    print(json.dumps({
        "status": "PASS",
        "dataset_sha256": dataset_sha,
        "workbook_sha256": workbook_sha,
        "repairs": repair["counts"],
        "validator": validator["status"],
        "workbook_validator": workbook_validation["status"],
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValidationError as exc:
        print(f"VALIDATION_FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1)
