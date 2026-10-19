"""Check the requested evidence and abstention behavior from actual agent outputs."""
import re


NEGATION = re.compile(r"无法|不能|不得|不可|尚未|未能|没有|未曾|并非|不应|不宜|拒绝|无权|不代表|不支持|缺乏|不会|未声称|未宣称|不曾")
ASSERTION = re.compile(r"结论|已核实|已确认|我已|已经|成功|状态")


def _unsupported_claim(answer, phrase):
    """True only when a forbidden phrase is asserted, rather than denied or quoted."""
    for clause in re.split(r"[。！？；;，,\n]|但|然而|不过|可是", answer):
        for match in re.finditer(re.escape(phrase), clause):
            before = clause[max(0, match.start() - 28):match.start()]
            after = clause[match.end():match.end() + 28]
            if NEGATION.search(before):
                continue
            quoted = (match.start() > 0 and match.end() < len(clause)
                      and clause[match.start() - 1] in '“"‘\''
                      and clause[match.end()] in '”"’\'')
            if quoted and (NEGATION.search(after) or not ASSERTION.search(before)):
                continue
            return True
    return False


def task_evidence(expected, actual, tags):
    answer = str(actual or "")
    expected = expected or {}
    if isinstance(expected, str):
        return 1.0 if expected in answer else 0.0
    if expected.get("errorCode"):
        return 1.0 if actual == expected["errorCode"] else 0.0
    required = expected.get("contains") or []
    forbidden = expected.get("notContains") or []
    if isinstance(required, str):
        required = [required]
    if isinstance(forbidden, str):
        forbidden = [forbidden]
    return 1.0 if all(word in answer for word in required) and not any(
        _unsupported_claim(answer, word) for word in forbidden) else 0.0
