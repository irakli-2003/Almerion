"""Collects the English UI strings of the desktop app into tools/i18n/catalog.json.

Every string literal in src/main/java (except the command-line version) is a candidate.
Literals joined with ``+`` become one pattern where each non-literal part is a wildcard
({A}, {B}, ...), so "Needs " + n + " gold" is translated as "Needs {A} gold".
Digits in plain strings become {0}, {1}, ... exactly like com.dnd.ui.i18n.TextTemplate.

Usage:  python tools/i18n/extract_strings.py            (writes the catalog)
        python tools/i18n/extract_strings.py --missing  CODE  (lists catalog keys a language lacks)
"""
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SRC = os.path.join(ROOT, 'src', 'main', 'java', 'com', 'dnd')
# The command-line version, the translation system itself, and printed sheets (always English).
EXCLUDED_DIRS = {os.path.join(SRC, 'cli'), os.path.join(SRC, 'ui', 'i18n')}
EXCLUDED_FILES = {os.path.join(SRC, 'ui', 'print', 'CharacterSheetPdf.java'),
                  os.path.join(SRC, 'github', 'WindowsDpapi.java')}
# record toString() output such as "Code[{A}]" and story markup tags such as "[GM NOTE]".
JUNK = re.compile(r'[A-Z]\w*\[.*\]|\[[A-Z][A-Z ]*\]|[/.]\S*', re.S)
CATALOG = os.path.join(ROOT, 'tools', 'i18n', 'catalog.json')
DICTS = os.path.join(ROOT, 'src', 'main', 'resources', 'i18n')

TOKEN = re.compile(r'''
    (?P<ws>\s+)
  | (?P<lc>//[^\n]*)
  | (?P<bc>/\*.*?\*/)
  | (?P<tb>"""[ \t]*\n.*?""")
  | (?P<str>"(?:[^"\\\n]|\\.)*")
  | (?P<chr>'(?:[^'\\\n]|\\.)+')
  | (?P<num>\d[\w.]*)
  | (?P<id>[A-Za-z_$][\w$]*)
  | (?P<op>->|::|\+\+|--|&&|\|\||[+\-*/%=!<>&|^]=|<<|>>>?|[^\s\w])
''', re.S | re.X)

# Calls whose string arguments are never shown to the user.
SILENT_CALLS = {
    'setStyle', 'setId', 'compile', 'getResource', 'getResourceAsStream', 'getLogger', 'log', 'fine',
    'finer', 'warning', 'info', 'severe', 'equals', 'equalsIgnoreCase', 'startsWith', 'endsWith',
    'contains', 'matches', 'replace', 'replaceAll', 'replaceFirst', 'split', 'path', 'has', 'get',
    'getProperty', 'setProperty', 'resolve', 'of', 'indexOf', 'lastIndexOf', 'addStyleClass',
    'getStyleClass', 'pseudoClass', 'getPseudoClass', 'lookup', 'lookupAll', 'loadFont', 'getenv',
    'put', 'putIfAbsent', 'remove', 'containsKey', 'header', 'setRequestProperty', 'uri', 'create',
    'JsonProperty', 'JsonAlias', 'JsonTypeName', 'Name', 'SuppressWarnings', 'Test', 'Named',
    'toExternalForm', 'getParameter', 'format', 'parse', 'decode', 'encode', 'forName', 'value',
}

ESCAPES = {'n': '\n', 't': '\t', 'r': '\r', '"': '"', "'": "'", '\\': '\\', 'b': '\b', 'f': '\f', 's': ' '}


def unescape(body):
    out, i = [], 0
    while i < len(body):
        c = body[i]
        if c == '\\' and i + 1 < len(body):
            n = body[i + 1]
            if n == 'u':
                j = i + 1
                while j < len(body) and body[j] == 'u':
                    j += 1
                out.append(chr(int(body[j:j + 4], 16)))
                i = j + 4
                continue
            if n == '\n':
                i += 2
                continue
            out.append(ESCAPES.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def text_block(raw):
    lines = raw[3:-3].split('\n')[1:]
    closing_indent = len(lines[-1]) - len(lines[-1].lstrip()) if lines else 0
    content = [l for l in lines if l.strip()]
    indent = min([len(l) - len(l.lstrip()) for l in content] + [closing_indent]) if content else 0
    text = '\n'.join(l[indent:].rstrip() for l in lines)
    return unescape(text)


def tokenize(src):
    tokens = []
    for m in TOKEN.finditer(src):
        kind = m.lastgroup
        if kind in ('ws', 'lc', 'bc'):
            continue
        val = m.group()
        line = src.count('\n', 0, m.start()) + 1
        if kind == 'str':
            tokens.append(('str', unescape(val[1:-1]), line))
        elif kind == 'tb':
            tokens.append(('str', text_block(val), line))
        else:
            tokens.append((kind, val, line))
    return tokens


def is_op(tokens, i, *values):
    return 0 <= i < len(tokens) and tokens[i][0] == 'op' and tokens[i][1] in values


def match_back(tokens, i, open_, close):
    depth = 0
    while i >= 0:
        if is_op(tokens, i, close):
            depth += 1
        elif is_op(tokens, i, open_):
            depth -= 1
            if depth == 0:
                return i
        i -= 1
    return 0


def match_fwd(tokens, i, open_, close):
    depth = 0
    while i < len(tokens):
        if is_op(tokens, i, open_):
            depth += 1
        elif is_op(tokens, i, close):
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(tokens) - 1


def operand_start(tokens, end):
    """Index where the operand ending at ``end`` starts."""
    i = end
    while True:
        if is_op(tokens, i, ')'):
            i = match_back(tokens, i, '(', ')')
            if i > 0 and tokens[i - 1][0] == 'id':
                i -= 1
                if i > 0 and tokens[i - 1][1] == 'new':
                    i -= 1
        elif is_op(tokens, i, ']'):
            i = match_back(tokens, i, '[', ']') - 1
            continue
        if is_op(tokens, i - 1, '.') and i - 2 >= 0 and (tokens[i - 2][0] in ('id', 'str') or is_op(tokens, i - 2, ')', ']')):
            i -= 2
            continue
        return i


def operand_end(tokens, start):
    """Index of the last token of the operand that starts at ``start``."""
    i = start
    while is_op(tokens, i, '!', '-'):
        i += 1
    if i < len(tokens) and tokens[i][1] == 'new':
        i += 1
    if is_op(tokens, i, '('):
        i = match_fwd(tokens, i, '(', ')')
    while True:
        if is_op(tokens, i + 1, '('):
            i = match_fwd(tokens, i + 1, '(', ')')
        elif is_op(tokens, i + 1, '['):
            i = match_fwd(tokens, i + 1, '[', ']')
        elif is_op(tokens, i + 1, '.') and i + 2 < len(tokens) and tokens[i + 2][0] == 'id':
            i += 2
        else:
            return i


def chain_around(tokens, i):
    """Operands (start, end) of the + chain that contains the literal at index i."""
    operands = [(i, operand_end(tokens, i))]
    start = i
    while is_op(tokens, start - 1, '+') and start - 2 >= 0:
        end = start - 2
        s = operand_start(tokens, end)
        operands.insert(0, (s, end))
        start = s
    end = operands[-1][1]
    while is_op(tokens, end + 1, '+') and end + 2 < len(tokens):
        s = end + 2
        e = operand_end(tokens, s)
        operands.append((s, e))
        end = e
    return operands


def silent(tokens, start):
    """True when the expression starting at ``start`` is an argument of a call that never shows it."""
    j = start - 1
    depth = 0
    while j >= 0:
        kind, val, _ = tokens[j]
        if kind == 'op' and val in (')', ']'):
            depth += 1
        elif kind == 'op' and val in ('(', '['):
            if depth == 0:
                return j > 0 and tokens[j - 1][0] == 'id' and tokens[j - 1][1] in SILENT_CALLS
            depth -= 1
        elif kind == 'op' and val in (';', '{', '}') and depth == 0:
            return False
        j -= 1
    return False


FORMAT = re.compile(r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXc%]')
DIGITS = re.compile(r'\d+')
WILDCARD_NAMES = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'


def letters(s):
    return sum(1 for c in s if c.isalpha())


def looks_technical(s):
    core = s.strip()
    if letters(core) == 0 or len(core) < 2:
        return True
    if '-fx-' in core or '://' in core or '\\' in core:
        return True
    if re.fullmatch(r'[a-z0-9_.\-/:#@$]+', core):
        return True
    if re.fullmatch(r'[\w$]+(\.[\w$]+)+', core):
        return True
    if ' ' not in core and re.search(r'\.(json|png|jpe?g|gif|css|txt|html|mp3|wav|m4a|ttf|otf|ico|jar|exe|properties|md|pdf)\b', core, re.I):
        return True
    if re.fullmatch(r'[A-Z0-9_]+', core) and '_' in core:
        return True
    if core.startswith(('{', '[', '<', '/')) and ' ' not in core:
        return True
    return False


def exact_key(text):
    core = text.strip()
    if '{' in core or '}' in core:
        return core
    n = [0]

    def rep(_):
        n[0] += 1
        return '{%d}' % (n[0] - 1)
    return DIGITS.sub(rep, core)


def build_pattern(parts):
    """parts: list of str (literal) or None (wildcard). Returns pattern text or None."""
    merged = []
    for p in parts:
        if p is None:
            if merged and merged[-1] is None:
                continue
            merged.append(None)
        elif merged and isinstance(merged[-1], str):
            merged[-1] += p
        else:
            merged.append(p)
    if all(isinstance(p, str) for p in merged):
        return None
    literal = ''.join(p for p in merged if p is not None)
    if letters(literal) < 3:
        return None
    out, w = [], 0
    for p in merged:
        if p is None:
            out.append('{' + WILDCARD_NAMES[min(w, 25)] + '}')
            w += 1
        else:
            out.append(DIGITS.sub('#', p) if False else p)
    text = ''.join(out).strip()
    if re.fullmatch(r'(\{[A-Z]\}|\W)*', text):
        return None
    return text


def format_to_pattern(s):
    parts, last = [], 0
    for m in FORMAT.finditer(s):
        if m.group() == '%%':
            continue
        parts.append(s[last:m.start()])
        parts.append(None)
        last = m.end()
    parts.append(s[last:])
    return build_pattern([p for p in parts if p != ''])


def renumber(piece):
    names = iter(WILDCARD_NAMES)
    mapping = {}

    def rep(m):
        if m.group(1) not in mapping:
            mapping[m.group(1)] = next(names)
        return '{' + mapping[m.group(1)] + '}'
    return re.sub(r'\{([A-Z])\}', rep, piece)


def extract():
    found = {}

    def add(key, kind, where):
        if not key or letters(re.sub(r'\{[A-Z0-9]\}',         '', key)) == 0 or JUNK.fullmatch(key):
            return
        entry = found.setdefault(key, {'key': key, 'kind': kind, 'where': []})
        if len(entry['where']) < 3 and where not in entry['where']:
            entry['where'].append(where)

    def add_text(text, kind, where):
        if '\n' in text:
            for piece in text.split('\n'):
                piece = piece.strip()
                if not piece or letters(re.sub(r'\{[A-Z]\}', '', piece)) < 2:
                    continue
                if re.search(r'\{[A-Z]\}', piece):
                    add(renumber(piece), 'pattern', where)
                elif not looks_technical(piece):
                    add(exact_key(piece), 'exact', where)
        elif kind == 'pattern':
            add(text, 'pattern', where)
        else:
            add(exact_key(text), 'exact', where)

    for dirpath, _dirnames, filenames in os.walk(SRC):
        if any(dirpath.startswith(x) for x in EXCLUDED_DIRS):
            continue
        for fn in filenames:
            if not fn.endswith('.java'):
                continue
            path = os.path.join(dirpath, fn)
            if path in EXCLUDED_FILES:
                continue
            rel = os.path.relpath(path, ROOT).replace('\\', '/')
            with open(path, encoding='utf-8') as f:
                tokens = tokenize(f.read())
            used = set()
            for i, (kind, val, line) in enumerate(tokens):
                if kind != 'str' or i in used:
                    continue
                if is_op(tokens, i - 1, '@') or (i > 1 and is_op(tokens, i - 1, '(') and is_op(tokens, i - 3, '@')):
                    continue
                operands = chain_around(tokens, i)
                for s, e in operands:
                    used.update(range(s, e + 1))
                if silent(tokens, operands[0][0]):
                    continue
                where = '%s:%d' % (rel, line)
                if len(operands) == 1 and operands[0][0] == operands[0][1]:
                    if looks_technical(val):
                        continue
                    if FORMAT.search(val):
                        p = format_to_pattern(val)
                        if p:
                            add_text(p, 'pattern', where)
                        continue
                    add_text(val.strip(), 'exact', where)
                    continue
                parts = [tokens[s][1] if s == e and tokens[s][0] == 'str' else None for s, e in operands]
                literals = [p for p in parts if p is not None and p.strip()]
                if not literals or all(looks_technical(p) for p in literals):
                    continue
                pattern = build_pattern(parts)
                if pattern is None:
                    if None not in parts:
                        joined = ''.join(parts)
                        if not looks_technical(joined):
                            add_text(joined.strip(), 'exact', where)
                    continue
                add_text(pattern, 'pattern', where)
    return sorted(found.values(), key=lambda e: e['key'].lower())


def write_catalog(entries):
    with open(CATALOG, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(entries, f, ensure_ascii=False, indent=1)
        f.write('\n')


def main():
    entries = extract()
    if '--missing' in sys.argv:
        code = sys.argv[sys.argv.index('--missing') + 1]
        path = os.path.join(DICTS, code + '.json')
        have = {}
        if os.path.exists(path):
            with open(path, encoding='utf-8') as f:
                have = json.load(f)
        missing = [e['key'] for e in entries if e['key'] not in have]
        print(json.dumps(missing, ensure_ascii=False, indent=1))
        return
    write_catalog(entries)
    exact = sum(1 for e in entries if e['kind'] == 'exact')
    print('%d strings (%d exact, %d patterns), %d words' % (
        len(entries), exact, len(entries) - exact, sum(len(e['key'].split()) for e in entries)))


if __name__ == '__main__':
    main()
