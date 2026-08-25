# -*- coding: utf-8 -*-
"""Apply the design output to both prototype files.

  Onboarding Platform.dc.html   the editable source
  Onboarding Platform.html      the standalone bundle (source is JSON-embedded)

The same edit list runs against both, so the two never drift. Two things about the
bundle's copy: the webfonts are inlined into <helmet>, and attributes are stored
already run through support.js's encodeCamelAttrs (onClick -> sc-camel-on-click),
so bundle edits go through camelize() first. Both files use LF.
"""
import json
import pathlib
import re
import shutil
import sys

from build_icons import ICONS

D = pathlib.Path(__file__).resolve().parent.parent.parent
DC = D / "Onboarding Platform.dc.html"
BUNDLE = D / "Onboarding Platform.html"
UUID = "f716e800-c2da-4ea4-a840-ce3f47169746"

P = {name: data[1] for name, data in ICONS.items()}

ACC = "oklch(0.52 0.16 274)"


def ico(name, size, stroke=1.5, extra=""):
    """Inline SVG for a fixed icon. camelCase viewBox is restored by the runtime."""
    return (
        f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" '
        f'stroke="currentColor" stroke-width="{stroke}" stroke-linecap="round" '
        f'stroke-linejoin="round" aria-hidden="true"{extra}>'
        f'<path d="{P[name]}"></path></svg>'
    )


def bound(expr, size, stroke=1.5):
    """Inline SVG whose path data comes from the data layer."""
    return (
        f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" '
        f'stroke="currentColor" stroke-width="{stroke}" stroke-linecap="round" '
        f'stroke-linejoin="round" aria-hidden="true">'
        f'<path d="{{{{ {expr} }}}}"></path></svg>'
    )


# ---------------------------------------------------------------- the edit list
# (label, before, after, expected_count)
def edits(nl):
    E = []
    add = lambda *a: E.append(a)

    # ---------------------------------------------------------------- 1. logo
    add("logo mark",
        '<div style="width: 26px; height: 26px; border-radius: 7px; background: '
        'oklch(0.52 0.16 274); display: grid; place-items: center; font-size: 13px; '
        'font-weight: 700; color: #fff;">O</div>',
        '<svg width="26" height="26" viewBox="0 0 32 32" style="flex: 0 0 26px;" '
        'role="img" aria-label="Onboard OS">'
        '<rect width="32" height="32" rx="9" fill="oklch(0.52 0.16 274)"></rect>'
        '<path d="M19.74 9.52A7.49 7.49 0 1 1 12.26 9.52" fill="none" stroke="#fff" '
        'stroke-width="3.01" stroke-linecap="round"></path>'
        '<circle cx="16" cy="8.51" r="1.5" fill="#fff"></circle></svg>', 1)

    # ------------------------------------------------------------ 2. rail nav
    add("rail nav icon",
        '<span style="width: 16px; text-align: center; font-size: 13px; opacity: 0.85;">'
        '{{ item.icon }}</span>',
        '<span style="width: 16px; height: 16px; flex: 0 0 16px; opacity: 0.85; '
        'display: grid; place-items: center;">' + bound("item.iconPath", 16) + '</span>', 1)

    add("rail nav data",
        "      ['dash', 'Dashboard', '\u25eb', null],",
        f"      ['dash', 'Dashboard', ICON.dashboard, null],", 1)
    add("rail nav data 2",
        "      ['customers', 'Customers', '\u2630', '48'],",
        "      ['customers', 'Customers', ICON.customers, '48'],", 1)
    add("rail nav data 3",
        "      ['workspace', 'Journey workspace', '\u25c8', null],",
        "      ['workspace', 'Journey workspace', ICON.journey, null],", 1)
    add("rail nav data 4",
        "      ['workflow', 'Workflow builder', '\u2699', null],",
        "      ['workflow', 'Workflow builder', ICON.workflow, null],", 1)
    add("rail nav data 5",
        "      ['reports', 'Reports', '\u25c9', null],",
        "      ['reports', 'Reports', ICON.reports, null],", 1)
    add("rail nav data 6",
        "      ['portal', 'Customer portal', '\u25d0', null],",
        "      ['portal', 'Customer portal', ICON.portal, null],", 1)
    add("rail nav data 7",
        "      ['notes', 'Design notes', '\u270e', null]",
        "      ['notes', 'Design notes', ICON.notes, null]", 1)

    add("rail nav mapping",
        "nav: navDefs.map(n => ({ label: n[1], icon: n[2], badge: n[3], "
        "go: this.go(n[0]), style: this.navStyle(S.screen === n[0]) })),",
        "nav: navDefs.map(n => ({ label: n[1], iconPath: n[2], badge: n[3], "
        "go: this.go(n[0]), style: this.navStyle(S.screen === n[0]) })),", 1)

    # ---------------------------------------------------------- 3. header bits
    add("header search icon",
        '<span style="font-size: 12px;">\u2315</span>',
        '<span style="display: grid; place-items: center; flex: 0 0 15px;">'
        + ico("search", 15) + '</span>', 1)

    add("header bell",
        "        \u25d4",
        '        ' + ico("bell", 16), 1)

    # ------------------------------------------------------- 4. activity feed
    add("feed actor",
        '<div style="width: 22px; height: 22px; flex: 0 0 22px; border-radius: 50%; '
        'background: #f2f0ec; display: grid; place-items: center; font-size: 9.5px; '
        'font-weight: 600; color: #4d4a46;">{{ e.who }}</div>',
        '<div style="{{ e.avatar }}">'
        '<sc-if value="{{ e.iconPath }}" hint-placeholder-val="{{ false }}">'
        + bound("e.iconPath", 13) +
        '</sc-if>'
        '<sc-if value="{{ e.who }}" hint-placeholder-val="{{ true }}">{{ e.who }}</sc-if>'
        '</div>', 1)

    add("feed automation entry",
        "{ who: '\u2699', text: 'Workflow advanced Baltica Freight to Technical Setup "
        "automatically', time: '38 min ago' },",
        "{ who: '', iconPath: ICON.automation, text: 'Workflow advanced Baltica Freight "
        "to Technical Setup automatically', time: '38 min ago' },", 1)

    # ------------------------------------------------- 5. milestone status dot
    add("milestone dot",
        '<div style="{{ m.dot }}">{{ m.dotGlyph }}</div>',
        '<div style="{{ m.dot }}">'
        '<sc-if value="{{ m.dotPath }}" hint-placeholder-val="{{ false }}">'
        + bound("m.dotPath", 14, 2.4) + '</sc-if></div>', 1)

    add("milestone dot data",
        "        dotGlyph: m.st === 'done' ? '\u2713' : m.st === 'blocked' ? '!' : '',",
        "        dotPath: m.st === 'done' ? ICON.check : m.st === 'blocked' "
        "? ICON.alert : '',", 1)

    # ----------------------------------------------------- 6. milestone chevron
    add("milestone chevron",
        '<span style="{{ m.chevStyle }}">\u25be</span>',
        '<span style="{{ m.chevStyle }}">' + ico("chevron-down", 15) + '</span>', 1)

    add("chevron style",
        "chevStyle: 'font-size: 11px; color: #9a968f; transition: transform 0.18s ease; "
        "transform: rotate(' + (isOpen ? '180deg' : '0deg') + ');',",
        "chevStyle: 'display: grid; place-items: center; color: #9a968f; "
        "transition: transform 0.18s ease; transform: rotate(' + "
        "(isOpen ? '180deg' : '0deg') + ');',", 1)

    # -------------------------------------------------------- 7. task checkbox
    add("task checkbox",
        '<button onClick="{{ t.toggle }}" style="{{ t.box }}">{{ t.check }}</button>',
        '<button onClick="{{ t.toggle }}" style="{{ t.box }}" aria-label="Toggle task">'
        '<sc-if value="{{ t.checkPath }}" hint-placeholder-val="{{ false }}">'
        + bound("t.checkPath", 11, 3) + '</sc-if></button>', 2)

    add("task checkbox data",
        "      check: done ? '\u2713' : '',",
        "      checkPath: done ? ICON.check : '',", 1)

    # ---------------------------------------------------------- 8. upload zone
    add("dropzone glyph",
        '<div style="font-size: 20px; color: #b5b0a7;">\u21a5</div>',
        '<div style="color: #99948b; display: flex; justify-content: center;">'
        + ico("upload", 24) + '</div>', 1)

    # -------------------------------------------------------- 9. branch arrow
    add("branch arrow",
        '<span style="font-family: \'IBM Plex Mono\', monospace; font-size: 9px; '
        'letter-spacing: 0.06em; color: oklch(0.42 0.16 274);">\u2192</span>',
        '<span style="display: grid; place-items: center; '
        'color: oklch(0.42 0.16 274);">' + ico("arrow-right", 13) + '</span>', 1)

    # ----------------------------------------------------- 10. stage controls
    add("stage up",
        '<button onClick="{{ s.up }}" style="width: 26px; height: 26px; border-radius: 7px; '
        'border: 1px solid #e6e3dd; background: #fff; cursor: pointer; font-size: 10px; '
        'color: #75726c;" style-hover="background: #f2f0ec;">\u25b2</button>',
        '<button onClick="{{ s.up }}" aria-label="Move stage up" style="width: 26px; '
        'height: 26px; border-radius: 7px; border: 1px solid #e6e3dd; background: #fff; '
        'cursor: pointer; color: #625f5a; display: grid; place-items: center; padding: 0;" '
        'style-hover="background: #f2f0ec;">' + ico("chevron-up", 13) + '</button>', 1)
    add("stage down",
        '<button onClick="{{ s.down }}" style="width: 26px; height: 26px; border-radius: 7px; '
        'border: 1px solid #e6e3dd; background: #fff; cursor: pointer; font-size: 10px; '
        'color: #75726c;" style-hover="background: #f2f0ec;">\u25bc</button>',
        '<button onClick="{{ s.down }}" aria-label="Move stage down" style="width: 26px; '
        'height: 26px; border-radius: 7px; border: 1px solid #e6e3dd; background: #fff; '
        'cursor: pointer; color: #625f5a; display: grid; place-items: center; padding: 0;" '
        'style-hover="background: #f2f0ec;">' + ico("chevron-down", 13) + '</button>', 1)
    add("stage remove",
        '<button onClick="{{ s.remove }}" style="width: 26px; height: 26px; '
        'border-radius: 7px; border: 1px solid #e6e3dd; background: #fff; cursor: pointer; '
        'font-size: 11px; color: #75726c;" style-hover="background: oklch(0.96 0.04 25); '
        'color: oklch(0.5 0.17 25);">\u2715</button>',
        '<button onClick="{{ s.remove }}" aria-label="Delete stage" style="width: 26px; '
        'height: 26px; border-radius: 7px; border: 1px solid #e6e3dd; background: #fff; '
        'cursor: pointer; color: #625f5a; display: grid; place-items: center; padding: 0;" '
        'style-hover="background: oklch(0.96 0.04 25); color: oklch(0.5 0.17 25);">'
        + ico("x", 13) + '</button>', 1)

    # -------------------------------------------------------- 11. add buttons
    add("new case chip",
        '<button style="height: 27px; padding: 0 10px; border-radius: 8px; '
        'border: 1px dashed #ccc7bf; background: transparent; font-family: Archivo, '
        'sans-serif; font-size: 11.5px; color: #75726c; cursor: pointer;" '
        'style-hover="border-color: #9a968f;">\uff0b New case</button>',
        '<button style="height: 27px; padding: 0 10px; border-radius: 8px; '
        'border: 1px dashed #ccc7bf; background: transparent; font-family: Archivo, '
        'sans-serif; font-size: 11.5px; color: #625f5a; cursor: pointer; '
        'display: inline-flex; align-items: center; gap: 5px;" '
        'style-hover="border-color: #716d67;">' + ico("plus", 13) + 'New case</button>', 1)

    add("add condition",
        '<button style="width: 100%; height: 30px; margin-top: 9px; border-radius: 8px; '
        'border: 1px dashed #ccc7bf; background: transparent; font-family: Archivo, '
        'sans-serif; font-size: 11.5px; color: #75726c; cursor: pointer;" '
        'style-hover="border-color: #9a968f;">\uff0b Add condition</button>',
        '<button style="width: 100%; height: 30px; margin-top: 9px; border-radius: 8px; '
        'border: 1px dashed #ccc7bf; background: transparent; font-family: Archivo, '
        'sans-serif; font-size: 11.5px; color: #625f5a; cursor: pointer; '
        'display: inline-flex; align-items: center; justify-content: center; gap: 5px;" '
        'style-hover="border-color: #716d67;">' + ico("plus", 13) + 'Add condition</button>', 1)

    # ------------------------------------------------------- 12. mobile tabs
    add("mobile tab icon",
        '<span style="font-size: 15px; color: {{ t.ink }};">{{ t.icon }}</span>',
        '<span style="color: {{ t.ink }}; display: grid; place-items: center;">'
        + bound("t.iconPath", 18) + '</span>', 1)

    add("mobile tab data",
        "        { icon: '\u25eb', label: 'Progress', ink: ACC },",
        "        { iconPath: ICON.dashboard, label: 'Progress', ink: ACC },", 1)
    add("mobile tab data 2",
        "        { icon: '\u2630', label: 'Tasks', ink: MUT },",
        "        { iconPath: ICON.tasks, label: 'Tasks', ink: MUT },", 1)
    add("mobile tab data 3",
        "        { icon: '\u2399', label: 'Documents', ink: MUT },",
        "        { iconPath: ICON.document, label: 'Documents', ink: MUT },", 1)
    add("mobile tab data 4",
        "        { icon: '\u25d4', label: 'Updates', ink: MUT }",
        "        { iconPath: ICON.bell, label: 'Updates', ink: MUT }", 1)

    return E


# ------------------------------------------------------- the ICON registry blob
def icon_registry(nl):
    """A named subset of the icon set, injected into the logic class."""
    picks = [
        ("dashboard", "layout-dashboard"), ("customers", "users"), ("journey", "journey"),
        ("workflow", "workflow"), ("reports", "chart-column"), ("portal", "portal-window"),
        ("notes", "pencil-line"), ("search", "search"), ("bell", "bell"),
        ("plus", "plus"), ("close", "x"), ("chevronUp", "chevron-up"),
        ("chevronDown", "chevron-down"), ("check", "check"), ("alert", "exclamation"),
        ("arrowRight", "arrow-right"), ("upload", "upload"), ("document", "file-text"),
        ("tasks", "check-square"), ("automation", "refresh-cw"),
    ]
    lines = [
        "// Icon path data — 24x24 grid, 1.5 stroke, single path each.",
        "// Full 56-icon set, sprite and gallery: design/03-icons/",
        "const ICON = {",
    ]
    for alias, name in picks:
        lines.append(f"  {alias}: '{P[name]}',")
    lines.append("};")
    return nl.join(lines)


# --------------------------------------------------------- token + a11y styles
def style_block(nl):
    return nl.join([
        "  /* ---- Design tokens -------------------------------------------------",
        "   * The full three-layer system (primitive -> semantic -> component),",
        "   * a dark theme and a Tailwind mapping live in design/02-tokens/.",
        "   * Declared here so the prototype names its values instead of only",
        "   * repeating literals inline.",
        "   */",
        "  :root {",
        "    --ob-bg-page: #f7f6f3;      --ob-bg-surface: #ffffff;",
        "    --ob-bg-surface-subtle: #faf9f7; --ob-bg-inset: #f4f2ee;",
        "    --ob-bg-rail: #17181c;      --ob-bg-rail-raised: #2b2c31;",
        "    --ob-border-default: #e6e3dd;   --ob-border-subtle: #f4f2ee;",
        "    --ob-border-panel: #efece7;     --ob-border-strong: #dbd7cf;",
        "    --ob-border-dashed: #ccc7bf;",
        "    --ob-text-primary: #191a1e;     --ob-text-secondary: #4d4a46;",
        "    --ob-text-muted: #625f5a;       --ob-text-faint: #76726c;",
        "    --ob-text-disabled: #7b766f;",
        "    --ob-accent: oklch(0.52 0.16 274);",
        "    --ob-accent-hover: oklch(0.45 0.16 274);",
        "    --ob-accent-tint: oklch(0.95 0.03 274);",
        "    --ob-accent-ink: oklch(0.42 0.16 274);",
        "    --ob-success: oklch(0.6 0.12 155); --ob-warning: oklch(0.72 0.14 70);",
        "    --ob-danger: oklch(0.57 0.17 25);",
        "    --ob-radius-card: 13px;     --ob-radius-control: 9px;",
        "    --ob-radius-pill: 20px;",
        "  }",
        "",
        "  /* ---- Focus visibility ----------------------------------------------",
        "   * The prototype shipped with no focus indicator at all; every control",
        "   * here is reachable by keyboard, so it needs one.",
        "   */",
        "  :where(button, a, input, [tabindex]):focus-visible {",
        "    outline: 2px solid oklch(0.52 0.16 274);",
        "    outline-offset: 2px;",
        "    border-radius: 5px;",
        "  }",
        "",
        "  /* ---- Reduced motion -------------------------------------------------",
        "   * Progress widths, panel entry and the live pulse are all decorative.",
        "   */",
        "  @media (prefers-reduced-motion: reduce) {",
        "    *, *::before, *::after {",
        "      animation-duration: 0.01ms !important;",
        "      animation-iteration-count: 1 !important;",
        "      transition-duration: 0.01ms !important;",
        "    }",
        "  }",
    ])


# ------------------------------------------- contrast fixes (see design/05-review)
COLOUR_FIXES = [
    ("#9a968f", "#716d67"),   # faint    2.80:1 -> 4.52:1 worst case (all 6 surfaces)
    ("#8a867f", "#625f5a"),   # stray one-off, folds into muted
    ("#75726c", "#625f5a"),   # muted    4.44:1 -> 5.58:1 worst case
    ("#b0aba3", "#716d67"),   # struck   2.28:1 -> 4.52:1 worst case
    # near-duplicate literals collapsed onto their token
    ("#efece6", "#efece7"),
    ("#ece9e3", "#eae7e1"),
    ("#ddd9d2", "#dbd7cf"),
    ("#d9d5ce", "#d6d1c9"),
    ("#c9c4bb", "#ccc7bf"),
    ("oklch(0.94 0.03 274)", "oklch(0.93 0.03 274)"),
    ("oklch(0.93 0.04 70)", "oklch(0.9 0.05 70)"),
    ("oklch(0.93 0.03 70)", "oklch(0.9 0.05 70)"),
    ("oklch(0.48 0.14 70)", "oklch(0.46 0.14 70)"),
    ("oklch(0.5 0.12 155)", "oklch(0.45 0.12 155)"),
]


CAMEL_ATTR_RE = re.compile(r"(\s)([a-z]+[A-Z][A-Za-z0-9]*)(\s*=)")


def camelize(s):
    """Mirror support.js encodeCamelAttrs: onClick= -> sc-camel-on-click=.

    The bundle stores the template already run through this transform, because it
    re-serialised the parsed DOM (HTML parsing would otherwise lowercase the
    attribute name and lose the camelCase). Idempotent: already-encoded
    attributes contain no capitals, so the pattern cannot match them twice.
    """
    return CAMEL_ATTR_RE.sub(
        lambda m: m.group(1) + "sc-camel-"
        + re.sub(r"[A-Z]", lambda c: "-" + c.group(0).lower(), m.group(2))
        + m.group(3),
        s,
    )


def apply(text, nl, label, xform=lambda s: s):
    report = []

    for name, before, after, expect in edits(nl):
        before, after = xform(before), xform(after)
        n = text.count(before)
        if n != expect:
            raise SystemExit(
                f"[{label}] edit '{name}': expected {expect} match(es), found {n}\n"
                f"  looking for: {before[:160]}"
            )
        text = text.replace(before, after)
        report.append((name, n))

    # inject the ICON registry at the top of the logic script
    anchor = "const ACC = 'oklch(0.52 0.16 274)';"
    if text.count(anchor) != 1:
        raise SystemExit(f"[{label}] could not find the script anchor")
    text = text.replace(anchor, icon_registry(nl) + nl + nl + anchor, 1)

    # inject tokens + a11y CSS at the end of the helmet style block
    css_anchor = ("  @keyframes pulseDot { 0%,100% { opacity: 1; } "
                  "50% { opacity: 0.35; } }")
    if text.count(css_anchor) != 1:
        raise SystemExit(f"[{label}] could not find the keyframes anchor")
    text = text.replace(css_anchor, css_anchor + nl + style_block(nl), 1)

    # contrast + collapse fixes
    for old, new in COLOUR_FIXES:
        c = text.count(old)
        if c:
            text = text.replace(old, new)
            report.append((f"colour {old} -> {new}", c))

    return text, report


def main():
    # ---- back up once, so the original handoff stays recoverable,
    #      and always start from that original so the script is re-runnable
    for f in (DC, BUNDLE):
        bak = f.with_suffix(f.suffix + ".orig")
        if not bak.exists():
            shutil.copy2(f, bak)
            print(f"backed up {f.name} -> {bak.name}")
        else:
            shutil.copy2(bak, f)
            print(f"restored {f.name} from {bak.name}")

    # ---- 1. the editable source
    src = DC.read_text(encoding="utf-8")
    new_src, rep = apply(src, "\n", "dc.html")
    DC.write_text(new_src, encoding="utf-8", newline="")
    print(f"\n{DC.name}: {len(rep)} edits applied")
    for name, n in rep:
        print(f"  - {name}  x{n}")

    # ---- 2. the bundle's embedded copy (same markup, attributes pre-encoded)
    raw = BUNDLE.read_text(encoding="utf-8")
    lines = raw.split("\n")
    idx = next(i for i, l in enumerate(lines)
               if l.startswith('  <script type="__bundler/template">'))
    tpl = json.loads(lines[idx + 1])
    new_tpl, rep2 = apply(tpl, "\n", "bundle", xform=camelize)
    payload = json.dumps(new_tpl, ensure_ascii=True).replace("</", "<\\u002F")
    lines[idx + 1] = payload
    BUNDLE.write_text("\n".join(lines), encoding="utf-8", newline="")
    print(f"\n{BUNDLE.name}: {len(rep2)} edits applied to the embedded template")

    # ---- 3. prove the two files still describe the same UI.
    #        Compare the body after </helmet> (the bundle inlines the webfonts
    #        into the helmet, so only that part legitimately differs), with the
    #        dc copy put through the same attribute encoding.
    check = json.loads(BUNDLE.read_text(encoding="utf-8").split("\n")[idx + 1])

    def split3(s):
        """markup region, then the logic script — they normalise differently."""
        body = s.split("</helmet>", 1)[1]
        markup, script = body.split("</x-dc>", 1)
        return markup, script

    dc_markup, dc_script = split3(new_src.replace("./support.js", UUID))
    bn_markup, bn_script = split3(check)

    # Markup: the bundler pre-encodes camelCase attributes and gives boolean
    # attributes an explicit empty value. Both predate these edits.
    dc_markup = camelize(dc_markup)
    dc_script = dc_script.replace(" data-dc-script ", ' data-dc-script="" ')

    # The logic script is followed by the document's closing tags, which the
    # bundler re-serialised differently. Compare only up to </script>.
    dc_script = dc_script.split("</script>", 1)[0]
    bn_script = bn_script.split("</script>", 1)[0]

    ta, tb = dc_markup + dc_script, bn_markup + bn_script
    same = ta == tb
    print(f"  markup region identical: {dc_markup == bn_markup}")
    print(f"  logic script identical:  {dc_script == bn_script}")
    print(f"\nbody after </helmet> identical in both files: {same}")
    if not same:
        for i, (x, y) in enumerate(zip(ta, tb)):
            if x != y:
                print("  first divergence:")
                print("   dc:", repr(ta[max(0, i - 90):i + 90]))
                print("   bn:", repr(tb[max(0, i - 90):i + 90]))
                break
        else:
            print(f"  prefix equal, lengths differ: dc={len(ta)} bundle={len(tb)}")
        sys.exit(1)

    # ---- 4. no placeholder glyphs left anywhere
    leftovers = {g: new_src.count(g) for g in "\u25eb\u2630\u25c8\u2699\u25c9\u25d0\u270e"
                 "\u2315\u25d4\uff0b\u2715\u25b2\u25bc\u2713\u2192\u21a5\u2399\u25be"
                 if new_src.count(g)}
    print(f"placeholder glyphs remaining in dc.html: {leftovers or 'none'}")


if __name__ == "__main__":
    main()
