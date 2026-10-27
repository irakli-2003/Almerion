"""Build-time translator: fills src/main/resources/i18n/<code>.json from the English catalog.

Uses Argos Translate (MIT licensed, runs fully offline after the language models are
downloaded). It is a build tool only - nothing from it ships inside the app; the app just
reads the finished JSON dictionaries.

Translation is incremental: strings already present in a dictionary are kept, only new ones
are translated, and strings that no longer exist in the code are removed.
Hand-made fixes go in tools/i18n/overrides/<code>.json and always win over machine output.

Georgian (ka) has no Argos model; its dictionary is maintained by hand/AI and is only
checked here, never written.

Usage (from the repository root):
    tools/i18n/.venv/Scripts/python tools/i18n/translate.py              all languages
    tools/i18n/.venv/Scripts/python tools/i18n/translate.py --lang de,fr only these
    ... translate.py --force    re-translate everything (overrides still apply)
    ... translate.py --check    only report missing strings; exit code 1 if any
"""
import argparse
import json
import os
import re
import sys
from concurrent.futures import ProcessPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import extract_strings  # noqa: E402

ROOT = extract_strings.ROOT
DICTS = extract_strings.DICTS
OVERRIDES = os.path.join(ROOT, 'tools', 'i18n', 'overrides')

# App language code -> Argos model code.
ARGOS = {
    'ar': 'ar', 'de': 'de', 'es': 'es', 'fr': 'fr', 'hi': 'hi', 'it': 'it', 'ja': 'ja',
    'ko': 'ko', 'pl': 'pl', 'pt-BR': 'pb', 'ru': 'ru', 'tr': 'tr', 'uk': 'uk', 'zh-CN': 'zh',
}
MAINTAINED_BY_HAND = ['ka']

PLACEHOLDER = re.compile(r'\{(?:[0-9]|[A-Z])\}')
SAMPLE_NAMES = ['Zorbo', 'Quimby', 'Telvar', 'Mirelle', 'Brakka', 'Osric']


def placeholders(text):
    return sorted(PLACEHOLDER.findall(text))


def load_json(path):
    if not os.path.exists(path):
        return {}
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def write_dict(code, data):
    os.makedirs(DICTS, exist_ok=True)
    with open(os.path.join(DICTS, code + '.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(dict(sorted(data.items())), f, ensure_ascii=False, indent=1)
        f.write('\n')


def ensure_model(to_code):
    import argostranslate.package as pkg
    if any(p.from_code == 'en' and p.to_code == to_code for p in pkg.get_installed_packages()):
        return
    pkg.update_package_index()
    match = [p for p in pkg.get_available_packages() if p.from_code == 'en' and p.to_code == to_code]
    if not match:
        raise SystemExit('No Argos model for en -> ' + to_code)
    print('  downloading Argos model en -> %s' % to_code, flush=True)
    pkg.install_from_path(match[0].download())


class Translator:
    def __init__(self, to_code):
        import argostranslate.translate as tr
        self.engine = tr.get_translation_from_codes('en', to_code)

    def _raw(self, text):
        return self.engine.translate(text)

    def _masked(self, text, token_for):
        mapping = {}

        def mask(m):
            token = token_for(m.group(), len(mapping))
            mapping[token] = m.group()
            return token
        masked = PLACEHOLDER.sub(mask, text)
        out = self._raw(masked)
        for token, ph in mapping.items():
            if out.count(token) != 1:
                return None
            out = out.replace(token, ph)
        return out

    def line(self, text):
        """Translates one line; returns None when placeholders cannot be kept intact."""
        core = text.strip()
        if not core:
            return text
        lead, trail = text[:len(text) - len(text.lstrip())], text[len(text.rstrip()):]
        wanted = placeholders(core)
        attempts = [lambda: self._raw(core)]
        if wanted:
            attempts += [
                lambda: self._masked(core, lambda ph, i: SAMPLE_NAMES[i % len(SAMPLE_NAMES)]),
                lambda: self._masked(core, lambda ph, i: '<x%d/>' % i),
                lambda: self._masked(core, lambda ph, i: '[%d]' % (i + 1)),
            ]
        for attempt in attempts:
            out = attempt()
            if out and placeholders(out) == wanted:
                return lead + polish(core, out.strip()) + trail
        return None

    def text(self, text):
        lines = [self.line(part) for part in text.split('\n')]
        return None if any(l is None for l in lines) else '\n'.join(lines)


def polish(source, out):
    """Keeps the source's typographic endings that the model tends to rewrite."""
    if source.endswith('…') and out.endswith('...'):
        out = out[:-3] + '…'
    elif source.endswith('…') and not out.endswith('…'):
        out = out.rstrip('.') + '…'
    if source.endswith(':') and not out.endswith((':', '：')):
        out = out.rstrip('.') + ':'
    if not source.endswith(('.', '。', '!', '?')) and out.endswith('.') and not out.endswith('...'):
        out = out[:-1]
    if source[:1].isupper() and out[:1].islower():
        out = out[:1].upper() + out[1:]
    return out


def translate_language(job):
    code, keys, force = job
    argos = ARGOS[code]
    ensure_model(argos)
    path = os.path.join(DICTS, code + '.json')
    existing = {} if force else load_json(path)
    overrides = load_json(os.path.join(OVERRIDES, code + '.json'))
    result = {k: v for k, v in existing.items() if k in keys}
    todo = [k for k in keys if k not in result and k not in overrides]
    engine = Translator(argos) if todo else None
    failed = []
    for i, key in enumerate(todo, 1):
        out = engine.text(key)
        if out is None or out == key:
            failed.append(key)
        else:
            result[key] = out
        if i % 100 == 0:
            print('  %s: %d/%d' % (code, i, len(todo)), flush=True)
            write_dict(code, {**result, **{k: v for k, v in overrides.items() if k in keys}})
    result.update({k: v for k, v in overrides.items() if k in keys})
    write_dict(code, result)
    return code, len(todo), failed, len(result)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--lang', help='comma-separated language codes (default: all)')
    ap.add_argument('--force', action='store_true', help='re-translate strings that are already translated')
    ap.add_argument('--check', action='store_true', help='only report missing strings')
    ap.add_argument('--jobs', type=int, default=1,
                    help='languages translated in parallel (default 1; each one needs ~1-2 GB of memory)')
    args = ap.parse_args()

    entries = extract_strings.extract()
    extract_strings.write_catalog(entries)
    keys = [e['key'] for e in entries]
    every = sorted(ARGOS) + MAINTAINED_BY_HAND
    codes = args.lang.split(',') if args.lang else every
    unknown = [c for c in codes if c not in every]
    if unknown:
        raise SystemExit('Unknown language code(s): ' + ', '.join(unknown))

    if args.check:
        missing_any = False
        for code in codes:
            have = load_json(os.path.join(DICTS, code + '.json'))
            missing = [k for k in keys if k not in have]
            missing_any |= bool(missing)
            print('%-6s %4d translated, %4d missing' % (code, len(keys) - len(missing), len(missing)))
        sys.exit(1 if missing_any else 0)

    for code in [c for c in codes if c in MAINTAINED_BY_HAND]:
        have = load_json(os.path.join(DICTS, code + '.json'))
        missing = [k for k in keys if k not in have]
        print('%s is maintained by hand: %d strings missing (list them with '
              'extract_strings.py --missing %s)' % (code, len(missing), code))

    jobs = [(c, keys, args.force) for c in codes if c in ARGOS]
    for code, _keys, _force in jobs:
        ensure_model(ARGOS[code])
    with ProcessPoolExecutor(max_workers=max(1, args.jobs)) as pool:
        for code, done, failed, total in pool.map(translate_language, jobs):
            print('%-6s translated %d new, %d kept in English (placeholders could not be kept), %d total'
                  % (code, done - len(failed), len(failed), total), flush=True)


if __name__ == '__main__':
    main()
