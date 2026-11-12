#!/usr/bin/env python3
"""
Builds the PayRecon PDFs from the Markdown documents and adds drawn diagrams.

    docs/PROJECT_EXPLAINED.md  ->  docs/PayRecon-Project-Explained.pdf
    docs/BUILD_PLAN.md         ->  docs/PayRecon-Build-Plan.pdf

The Markdown files stay the single source of truth; this script only renders them.
  <!-- diagram: name -->                      a drawn diagram goes here (invisible on GitHub)
  <!-- pdf:skip --> ... <!-- /pdf:skip -->    left out of the PDF (ASCII drawings the diagrams replace)

Run from the project folder (any Python 3.9+):
    python3 -m venv .venv-docs && .venv-docs/bin/pip install "reportlab<4.3"
    .venv-docs/bin/python tools/make_pdfs.py
"""
import math
import os
import re
from xml.sax.saxutils import escape

from reportlab.graphics.shapes import Circle, Drawing, Line, PolyLine, Polygon, Rect, String
from reportlab.lib import colors
from reportlab.lib.colors import HexColor
from reportlab.lib.enums import TA_CENTER, TA_RIGHT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.pdfmetrics import stringWidth
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (BaseDocTemplate, Frame, KeepTogether, NextPageTemplate, PageBreak, PageTemplate,
                                Paragraph, Spacer, Table, TableStyle, XPreformatted)
from reportlab.platypus.flowables import HRFlowable
from reportlab.platypus.tableofcontents import TableOfContents

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PAGE_W, PAGE_H = A4
MARGIN_X = 50
W = PAGE_W - 2 * MARGIN_X  # usable width, also the width of every diagram

# ---------------------------------------------------------------------------------------------- fonts

F = {}
FONT_FILES = {
    'Body': [('/System/Library/Fonts/Helvetica.ttc', 0), ('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 0)],
    'BodyBold': [('/System/Library/Fonts/Helvetica.ttc', 1), ('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 0)],
    'BodyItalic': [('/System/Library/Fonts/Helvetica.ttc', 2),
                   ('/usr/share/fonts/truetype/dejavu/DejaVuSans-Oblique.ttf', 0)],
    'BodyBoldItalic': [('/System/Library/Fonts/Helvetica.ttc', 3),
                       ('/usr/share/fonts/truetype/dejavu/DejaVuSans-BoldOblique.ttf', 0)],
    'Mono': [('/System/Library/Fonts/Menlo.ttc', 0), ('/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf', 0)],
    'MonoBold': [('/System/Library/Fonts/Menlo.ttc', 1),
                 ('/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf', 0)],
    'Sym': [('/System/Library/Fonts/Supplemental/Arial Unicode.ttf', 0),
            ('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 0)],
}
BUILTIN = {'Body': 'Helvetica', 'BodyBold': 'Helvetica-Bold', 'BodyItalic': 'Helvetica-Oblique',
           'BodyBoldItalic': 'Helvetica-BoldOblique', 'Mono': 'Courier', 'MonoBold': 'Courier-Bold',
           'Sym': 'Helvetica'}


def register_fonts():
    for name, candidates in FONT_FILES.items():
        F[name] = BUILTIN[name]
        for path, index in candidates:
            if os.path.exists(path):
                try:
                    pdfmetrics.registerFont(TTFont('PR-' + name, path, subfontIndex=index))
                    F[name] = 'PR-' + name
                    break
                except Exception:
                    continue
    pdfmetrics.registerFontFamily(F['Body'], normal=F['Body'], bold=F['BodyBold'], italic=F['BodyItalic'],
                                  boldItalic=F['BodyBoldItalic'])
    pdfmetrics.registerFontFamily(F['Mono'], normal=F['Mono'], bold=F['MonoBold'], italic=F['Mono'],
                                  boldItalic=F['MonoBold'])


_cmaps = {}


def has_glyph(font, ch):
    if font not in _cmaps:
        face = getattr(pdfmetrics.getFont(font), 'face', None)
        c2g = getattr(face, 'charToGlyph', None)
        _cmaps[font] = set(c2g.keys()) if c2g else None
    cmap = _cmaps[font]
    return ord(ch) < 256 if cmap is None else ord(ch) in cmap


def glyph_fix(markup, base):
    """Characters the base font lacks (for example the rupee sign in Menlo) are drawn with another font."""
    out, in_tag = [], False
    for ch in markup:
        if ch == '<':
            in_tag = True
        if in_tag or ord(ch) < 128 or has_glyph(F[base], ch):
            out.append(ch)
        else:
            for alt in ('Body', 'Sym', 'Mono'):
                if has_glyph(F[alt], ch):
                    out.append('<font name="%s">%s</font>' % (F[alt], ch))
                    break
            else:
                out.append('?')
        if ch == '>':
            in_tag = False
    return ''.join(out)


# ---------------------------------------------------------------------------------------------- styles

S = {}


def make_styles():
    ink = HexColor('#1f2937')
    S['body'] = ParagraphStyle('body', fontName=F['Body'], fontSize=9.6, leading=13.8, spaceAfter=5.5, textColor=ink)
    S['H2'] = ParagraphStyle('H2', fontName=F['BodyBold'], fontSize=15, leading=19, textColor=HexColor('#0f172a'),
                             spaceBefore=16, spaceAfter=2, keepWithNext=1)
    S['H3'] = ParagraphStyle('H3', fontName=F['BodyBold'], fontSize=11.4, leading=15, textColor=HexColor('#1d4ed8'),
                             spaceBefore=11, spaceAfter=4, keepWithNext=1)
    S['H4'] = ParagraphStyle('H4', fontName=F['BodyBold'], fontSize=10, leading=13, textColor=HexColor('#0f172a'),
                             spaceBefore=8, spaceAfter=3, keepWithNext=1)
    S['bullet'] = ParagraphStyle('bullet', parent=S['body'], spaceAfter=2.6, bulletFontName=F['Body'],
                                 bulletFontSize=9.6)
    S['cell'] = ParagraphStyle('cell', fontName=F['Body'], fontSize=8.4, leading=11, textColor=ink)
    S['cellRight'] = ParagraphStyle('cellRight', parent=S['cell'], alignment=TA_RIGHT)
    S['cellHead'] = ParagraphStyle('cellHead', parent=S['cell'], fontName=F['BodyBold'], textColor=colors.white)
    S['code'] = ParagraphStyle('code', fontName=F['Mono'], fontSize=7.3, leading=9.4, textColor=HexColor('#0f172a'))
    S['caption'] = ParagraphStyle('caption', fontName=F['BodyItalic'], fontSize=8.2, leading=11,
                                  textColor=HexColor('#475569'), alignment=TA_CENTER, spaceBefore=4, spaceAfter=12)
    S['quote'] = ParagraphStyle('quote', parent=S['body'], fontName=F['BodyItalic'], spaceAfter=0)
    S['tocTitle'] = ParagraphStyle('tocTitle', fontName=F['BodyBold'], fontSize=16, leading=20,
                                   textColor=HexColor('#0f172a'), spaceAfter=12)
    S['toc0'] = ParagraphStyle('toc0', fontName=F['BodyBold'], fontSize=10.2, leading=15, textColor=HexColor('#0f172a'))
    S['toc1'] = ParagraphStyle('toc1', fontName=F['Body'], fontSize=8.8, leading=12, leftIndent=16,
                               textColor=HexColor('#334155'))
    S['coverH'] = ParagraphStyle('coverH', fontName=F['BodyBold'], fontSize=12, leading=16,
                                 textColor=HexColor('#0f172a'), spaceAfter=6)


# ---------------------------------------------------------------------------------------------- markdown

LIST_RE = re.compile(r'^(\s*)([-*]|\d+\.) (.*)$')


def is_block_start(line):
    s = line.strip()
    return (s.startswith('#') or s.startswith('```') or s.startswith('|') or s.startswith('>')
            or s.startswith('<!--') or s in ('---', '***') or bool(LIST_RE.match(line)))


def split_row(row):
    cells = re.split(r'(?<!\\)\|', row.strip().strip('|'))
    return [c.strip().replace('\\|', '|') for c in cells]


def parse_markdown(text):
    blocks, lines, i, skip = [], text.split('\n'), 0, False
    while i < len(lines):
        line = lines[i]
        s = line.strip()
        if s == '<!-- pdf:skip -->':
            skip = True
        elif s == '<!-- /pdf:skip -->':
            skip = False
        if skip or s in ('<!-- pdf:skip -->', '<!-- /pdf:skip -->'):
            i += 1
            continue
        m = re.match(r'<!--\s*diagram:\s*(\w+)\s*-->', s)
        if m:
            blocks.append(('diagram', m.group(1)))
            i += 1
        elif s.startswith('<!--'):
            i += 1
        elif s.startswith('```'):
            code, i = [], i + 1
            while i < len(lines) and not lines[i].strip().startswith('```'):
                code.append(lines[i])
                i += 1
            blocks.append(('code', code))
            i += 1
        elif re.match(r'^#{1,4} ', s):
            level = len(s) - len(s.lstrip('#'))
            blocks.append(('h', level, s[level:].strip()))
            i += 1
        elif s in ('---', '***'):
            blocks.append(('hr',))
            i += 1
        elif s.startswith('|'):
            rows = []
            while i < len(lines) and lines[i].strip().startswith('|'):
                rows.append(split_row(lines[i]))
                i += 1
            header, aligns, body = rows[0], ['left'] * len(rows[0]), []
            for r in rows[1:]:
                if all(re.match(r'^:?-{2,}:?$', c.replace(' ', '')) for c in r if c):
                    aligns = ['right' if c.endswith(':') and not c.startswith(':') else 'left' for c in r]
                else:
                    body.append(r)
            blocks.append(('table', header, aligns, body))
        elif s.startswith('>'):
            quote = []
            while i < len(lines) and lines[i].strip().startswith('>'):
                quote.append(lines[i].strip()[1:].strip())
                i += 1
            blocks.append(('quote', ' '.join(quote)))
        elif LIST_RE.match(line):
            items = []
            while i < len(lines) and LIST_RE.match(lines[i]):
                m2 = LIST_RE.match(lines[i])
                items.append((len(m2.group(1)), m2.group(2)[0].isdigit(), m2.group(3)))
                i += 1
            blocks.append(('list', items))
        elif not s:
            i += 1
        else:
            para = [s]
            i += 1
            while i < len(lines) and lines[i].strip() and not is_block_start(lines[i]):
                para.append(lines[i].strip())
                i += 1
            blocks.append(('p', ' '.join(para)))
    return blocks


def inline(text, base='Body', code_size=8.7):
    # Code spans are swapped for placeholders first, so **bold** around a `code span` still works.
    codes = []

    def stash(m):
        codes.append(m.group(1))
        return '\x00%d\x00' % (len(codes) - 1)

    def unstash(m):
        code = glyph_fix(escape(codes[int(m.group(1))]), 'Mono')
        return '<font name="%s" size="%s" color="#9a3412">%s</font>' % (F['Mono'], code_size, code)

    return re.sub('\x00(\\d+)\x00', unstash, fmt_text(re.sub(r'`([^`]+)`', stash, text), base))


PDF_TWINS = {'PROJECT_EXPLAINED.md': 'PayRecon-Project-Explained.pdf', 'BUILD_PLAN.md': 'PayRecon-Build-Plan.pdf'}


def fmt_text(t, base):
    t = escape(t)
    for md, pdf in PDF_TWINS.items():  # inside a PDF, point readers to the other PDF, not the Markdown file
        t = t.replace('[%s](%s)' % (md, md), '[%s](%s)' % (pdf, pdf))
    t = re.sub(r'\[([^\]]+)\]\((https?://[^)\s]+)\)', r'<link href="\2" color="#1d4ed8"><u>\1</u></link>', t)
    t = re.sub(r'\[([^\]]+)\]\(([^)\s]+)\)', r'<font color="#1d4ed8">\1</font>', t)
    t = re.sub(r'\*\*(.+?)\*\*', r'<b>\1</b>', t)
    t = re.sub(r'(?<![\w*])\*(?![\s*])(.+?)(?<![\s*])\*(?![\w*])', r'<i>\1</i>', t)
    return glyph_fix(t, base)


def plain(t):
    t = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', t)
    return re.sub(r'[*`]', '', t)


# ---------------------------------------------------------------------------------------------- blocks

def render_list(items):
    out, counters = [], {}
    for indent, ordered, text in items:
        level = min(indent // 2, 3)
        for k in [k for k in counters if k > level]:
            del counters[k]
        if ordered:
            counters[level] = counters.get(level, 0) + 1
            bullet = '%d.' % counters[level]
        else:
            bullet = '•' if level == 0 else '–'
        style = ParagraphStyle('li%d' % level, parent=S['bullet'], leftIndent=16 + level * 15,
                               bulletIndent=4 + level * 15)
        out.append(Paragraph(inline(text), style, bulletText=bullet))
    out.append(Spacer(1, 4))
    return out


def cell_widths(text, bold=False):
    """(width of the longest word, width of the whole text) of one table cell, in points."""
    font = F['BodyBold'] if bold else F['Body']
    longest, total = 0.0, 0.0
    for i, part in enumerate(re.split(r'`([^`]+)`', re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', text))):
        f, size = (F['Mono'], 7.8) if i % 2 else (font, 8.4)
        part = part.replace('**', '').replace('*', '') if not i % 2 else part
        for word in part.split():
            longest = max(longest, stringWidth(word, f, size))
        total += stringWidth(part, f, size)
    return longest, total


def render_table(header, aligns, rows):
    n, pad = len(header), 12
    min_w, nat_w = [], []
    for c in range(n):
        cells = [cell_widths(header[c], bold=True)] + [cell_widths(r[c]) for r in rows if c < len(r)]
        min_w.append(max(x[0] for x in cells) + pad)
        nat_w.append(min(max(x[1] for x in cells) + pad, W * 0.6))
    # Like a web browser: every column is at least as wide as its longest word, and the space left over
    # goes to the columns with the most text.
    if sum(nat_w) <= W:
        widths = [w * W / sum(nat_w) for w in nat_w]
    else:
        extra, want = W - sum(min_w), [max(nat_w[c] - min_w[c], 0.1) for c in range(n)]
        widths = [min_w[c] + max(extra, 0) * want[c] / sum(want) for c in range(n)]
        widths = [w * W / sum(widths) for w in widths]
    data = [[Paragraph(inline(h, code_size=7.8), S['cellHead']) for h in header]]
    for r in rows:
        data.append([Paragraph(inline(r[c] if c < len(r) else '', code_size=7.8),
                               S['cellRight'] if aligns[c] == 'right' else S['cell']) for c in range(n)])
    table = Table(data, colWidths=widths, repeatRows=1, hAlign='LEFT')
    style = [('BACKGROUND', (0, 0), (-1, 0), HexColor('#1e293b')), ('VALIGN', (0, 0), (-1, -1), 'TOP'),
             ('GRID', (0, 0), (-1, -1), 0.4, HexColor('#cbd5e1')), ('TOPPADDING', (0, 0), (-1, -1), 3.5),
             ('BOTTOMPADDING', (0, 0), (-1, -1), 3.5), ('LEFTPADDING', (0, 0), (-1, -1), 5),
             ('RIGHTPADDING', (0, 0), (-1, -1), 5)]
    for i in range(2, len(data), 2):
        style.append(('BACKGROUND', (0, i), (-1, i), HexColor('#f8fafc')))
    table.setStyle(TableStyle(style))
    return [table, Spacer(1, 9)]


def render_code(lines):
    max_chars = int((W - 20) / stringWidth('M', F['Mono'], S['code'].fontSize))
    wrapped = []
    for ln in lines:
        ln = ln.rstrip().replace('\t', '    ')
        while len(ln) > max_chars:
            # Break after the last comma or space that fits, so words like "auth_code" are not cut in half.
            cut = max(ln.rfind(',', 0, max_chars), ln.rfind(' ', 0, max_chars)) + 1
            cut = cut if cut > max_chars * 0.6 else max_chars
            wrapped.append(ln[:cut])
            ln = '  ' + ln[cut:]
        wrapped.append(ln)
    markup = '\n'.join(glyph_fix(escape(ln), 'Mono') for ln in wrapped)
    box = Table([[XPreformatted(markup, S['code'])]], colWidths=[W])
    box.setStyle(TableStyle([('BACKGROUND', (0, 0), (-1, -1), HexColor('#f1f5f9')),
                             ('BOX', (0, 0), (-1, -1), 0.5, HexColor('#cbd5e1')),
                             ('LEFTPADDING', (0, 0), (-1, -1), 8), ('RIGHTPADDING', (0, 0), (-1, -1), 8),
                             ('TOPPADDING', (0, 0), (-1, -1), 6), ('BOTTOMPADDING', (0, 0), (-1, -1), 6)]))
    return [box, Spacer(1, 8)]


def render_quote(text):
    box = Table([[Paragraph(inline(text), S['quote'])]], colWidths=[W])
    box.setStyle(TableStyle([('LINEBEFORE', (0, 0), (0, -1), 3, HexColor('#3b82f6')),
                             ('BACKGROUND', (0, 0), (-1, -1), HexColor('#eff6ff')),
                             ('LEFTPADDING', (0, 0), (-1, -1), 10), ('RIGHTPADDING', (0, 0), (-1, -1), 8),
                             ('TOPPADDING', (0, 0), (-1, -1), 6), ('BOTTOMPADDING', (0, 0), (-1, -1), 6)]))
    return [box, Spacer(1, 8)]


def render(blocks, counter):
    story = []
    for i, b in enumerate(blocks):
        kind = b[0]
        next_kind = blocks[i + 1][0] if i + 1 < len(blocks) else None
        if kind == 'h':
            level, text = b[1], b[2]
            if level == 1:
                continue
            style = S['H2'] if level == 2 else S['H3'] if level == 3 else S['H4']
            story.append(Paragraph(inline(text), style))
            if level == 2:
                rule = HRFlowable(width='100%', thickness=1.1, color=HexColor('#cbd5e1'), spaceBefore=2, spaceAfter=8)
                rule.keepWithNext = 1
                story.append(rule)
        elif kind == 'p':
            para = Paragraph(inline(b[1]), S['body'])
            # A sentence that introduces a figure or code block ("Here:") moves to the next page with it.
            para.keepWithNext = 1 if next_kind in ('diagram', 'code') else 0
            story.append(para)
        elif kind == 'list':
            story.extend(render_list(b[1]))
        elif kind == 'table':
            story.extend(render_table(b[1], b[2], b[3]))
        elif kind == 'code':
            story.extend(render_code(b[1]))
        elif kind == 'quote':
            story.extend(render_quote(b[1]))
        elif kind == 'hr':
            story.append(Spacer(1, 2))
        elif kind == 'diagram':
            if b[1] not in DIAGRAMS:
                raise SystemExit('Unknown diagram: ' + b[1])
            flowable, caption = DIAGRAMS[b[1]]()
            counter[0] += 1
            cap = Paragraph(inline('Figure %d. %s' % (counter[0], caption)), S['caption'])
            # One table cell holding picture + caption: it never splits, and (unlike KeepTogether) a heading's
            # keepWithNext can pull it along, so a heading is never left alone at the bottom of a page.
            fig = Table([[[flowable, cap]]], colWidths=[W])
            fig.setStyle(TableStyle([('LEFTPADDING', (0, 0), (-1, -1), 0), ('RIGHTPADDING', (0, 0), (-1, -1), 0),
                                     ('TOPPADDING', (0, 0), (-1, -1), 4), ('BOTTOMPADDING', (0, 0), (-1, -1), 10)]))
            story.append(fig)
    return story


# ---------------------------------------------------------------------------------------------- drawing helpers

PAL = {
    'mysql': ('#1d4ed8', '#dbeafe'), 'mr': ('#c2410c', '#ffedd5'), 'hive': ('#a16207', '#fef3c7'),
    'mongo': ('#15803d', '#dcfce7'), 'api': ('#6d28d9', '#ede9fe'), 'bank': ('#334155', '#e2e8f0'),
    'red': ('#b91c1c', '#fee2e2'), 'grey': ('#475569', '#f8fafc'), 'teal': ('#0f766e', '#ccfbf1'),
    'green': ('#15803d', '#dcfce7'),
}
INK = '#1f2937'


def col(c):
    return HexColor(c) if isinstance(c, str) else c


def fit(text, font, size, max_w, min_size=5.2):
    while size > min_size and stringWidth(text, font, size) > max_w:
        size -= 0.2
    return size


def draw_text(d, x, y, text, font, size, color, anchor='middle'):
    """Adds text to a drawing. Characters the font lacks (like the rupee sign in Menlo) use a fallback font."""
    runs = []
    for ch in text:
        f = font
        if ord(ch) >= 128 and not has_glyph(font, ch):
            f = next((F[a] for a in ('Body', 'Sym') if has_glyph(F[a], ch)), font)
        if runs and runs[-1][0] == f:
            runs[-1][1] += ch
        else:
            runs.append([f, ch])
    if len(runs) <= 1:
        d.add(String(x, y, text, fontName=font, fontSize=size, fillColor=col(color), textAnchor=anchor))
        return
    widths = [stringWidth(t, f, size) for f, t in runs]
    start = x - sum(widths) / 2 if anchor == 'middle' else x - sum(widths) if anchor == 'end' else x
    for (f, t), w in zip(runs, widths):
        d.add(String(start, y, t, fontName=f, fontSize=size, fillColor=col(color), textAnchor='start'))
        start += w


def label(d, x, y, text, size=7.2, bold=False, italic=False, color=INK, anchor='middle', max_w=None, mono=False):
    font = F['Mono'] if mono else F['BodyBold'] if bold else F['BodyItalic'] if italic else F['Body']
    if max_w:
        size = fit(text, font, size, max_w)
    draw_text(d, x, y, text, font, size, color, anchor)


def box(d, x, y, w, h, title='', lines=(), kind='grey', tsize=8.6, lsize=7.0, r=6, fill=None, mono=False,
        tcolor=None):
    stroke, light = PAL[kind]
    d.add(Rect(x, y, w, h, rx=r, ry=r, fillColor=col(fill or light), strokeColor=col(stroke), strokeWidth=1.1))
    gap = 2.6
    total = (tsize + 3 if title else 0) + len(lines) * (lsize + gap) - (gap if lines else 0)
    cur = y + h / 2 + total / 2
    if title:
        size = fit(title, F['BodyBold'], tsize, w - 8)
        cur -= tsize * 0.8
        draw_text(d, x + w / 2, cur, title, F['BodyBold'], size, tcolor or stroke)
        cur -= tsize * 0.2 + 3
    font = F['Mono'] if mono else F['Body']
    for ln in lines:
        size = fit(ln, font, lsize, w - 8)
        cur -= lsize * 0.8
        draw_text(d, x + w / 2, cur, ln, font, size, INK)
        cur -= lsize * 0.2 + gap


def _head(d, x1, y1, x2, y2, c, head=5.5):
    a = math.atan2(y2 - y1, x2 - x1)
    pts = [x2, y2,
           x2 - head * math.cos(a) + head * 0.45 * math.sin(a), y2 - head * math.sin(a) - head * 0.45 * math.cos(a),
           x2 - head * math.cos(a) - head * 0.45 * math.sin(a), y2 - head * math.sin(a) + head * 0.45 * math.cos(a)]
    d.add(Polygon(pts, fillColor=c, strokeColor=c, strokeWidth=0.5))


def arrow(d, x1, y1, x2, y2, text=None, color='#475569', width=1.1, dash=None, dx=0, dy=3.5, size=6.8,
          tcolor='#334155', bold=False):
    c = col(color)
    d.add(Line(x1, y1, x2, y2, strokeColor=c, strokeWidth=width, strokeDashArray=dash))
    _head(d, x1, y1, x2, y2, c)
    if text:
        label(d, (x1 + x2) / 2 + dx, (y1 + y2) / 2 + dy, text, size=size, color=tcolor, bold=bold)


def elbow(d, pts, color='#475569', dash=None):
    c = col(color)
    d.add(PolyLine(pts, strokeColor=c, strokeWidth=1.1, strokeDashArray=dash))
    _head(d, pts[-4], pts[-3], pts[-2], pts[-1], c)


def panel(d, x, y, w, h, fill='#f8fafc', stroke='#94a3b8', dash=None, r=8):
    d.add(Rect(x, y, w, h, rx=r, ry=r, fillColor=col(fill), strokeColor=col(stroke), strokeWidth=0.9,
               strokeDashArray=dash))


def sym(d, x, y, ch, size=10, color=INK):
    d.add(String(x, y, ch, fontName=F['Sym'], fontSize=size, fillColor=col(color), textAnchor='middle'))


# ---------------------------------------------------------------------------------------------- diagrams

def d_money_flow():
    d = Drawing(W, 182)
    bw, y, h = 88, 104, 60
    gap = (W - 4 * bw) / 3
    xs = [i * (bw + gap) for i in range(4)]
    box(d, xs[0], y, bw, h, 'Customer', ['Riya buys shoes', 'pays ₹1,000 (UPI)'], 'grey')
    box(d, xs[1], y, bw, h, 'HDFC Bank', ['the acquiring bank', 'collects the money'], 'bank')
    box(d, xs[2], y, bw, h, 'PayRecon', ['payment aggregator', 'keeps a ₹20 fee'], 'api')
    box(d, xs[3], y, bw, h, 'Sharma Footwear', ['the shop', 'receives ₹980'], 'grey')
    my = y + h / 2
    for i, (top, bottom) in enumerate((('pays', '₹1,000'), ('next day', '₹1,000 + file'), ('payout', '₹980'))):
        x1, x2 = xs[i] + bw, xs[i + 1]
        arrow(d, x1, my, x2, my, top, dy=4)
        label(d, (x1 + x2) / 2, my - 11, bottom, size=6.8, max_w=gap - 2)
    cx0, cx2 = xs[0] + bw / 2, xs[2] + bw / 2
    elbow(d, [cx0, y, cx0, 82, cx2, 82, cx2, y], color='#6d28d9', dash=[3, 2])
    label(d, (cx0 + cx2) / 2, 86, "the payment is recorded at once in PayRecon's ledger (MySQL)", size=6.9,
          color='#6d28d9')
    box(d, 0, 8, W, 50, 'Reconciliation (every day)',
        ["Does the bank's settlement file match PayRecon's own ledger?", 'Find and explain every difference.'],
        'red', tsize=9, lsize=7.6)
    return d, 'How money moves for one ₹1,000 payment, and where reconciliation fits in.'


def d_architecture():
    d = Drawing(W, 252)
    box(d, 0, 188, 118, 56, 'Data generator', ['1 month of payments', '+ an answer key'], 'teal')
    box(d, 0, 74, 118, 72, 'Bank files', ['HDFC: CSV', 'ICICI: pipe-separated', 'CARDNET: JSON lines'], 'bank')
    box(d, 168, 182, 160, 66, 'MySQL', ['double-entry ledger', 'never-charge-twice keys', 'exceptions work queue'],
        'mysql')
    box(d, 168, 56, 160, 86, 'Hadoop MapReduce',
        ['job 1: exact reference', 'job 2: amount + date window', 'job 3: payout batches'], 'mr')
    box(d, 377, 188, 118, 56, 'REST API', ['payments + refunds', 'safe to retry'], 'api')
    box(d, 377, 104, 118, 56, 'Hive', ['month-end close', '5 SQL reports'], 'hive')
    box(d, 377, 14, 118, 66, 'MongoDB', ['bank records as received', 'merchant statements'], 'mongo')
    arrow(d, 118, 216, 168, 216, 'writes')
    arrow(d, 59, 188, 59, 146, 'writes', dx=16, dy=-2)
    arrow(d, 377, 216, 328, 216, 'books')
    arrow(d, 215, 182, 215, 142, '1 extract', dx=-24, dy=-2)
    arrow(d, 281, 142, 281, 182, '3 exceptions', dx=32, dy=2)
    label(d, 313, 155, '4 book cash', size=6.8, color='#334155')
    arrow(d, 118, 104, 168, 104, '2 match')
    arrow(d, 328, 116, 377, 128, '5 publish', dy=5)
    arrow(d, 328, 76, 377, 46, '6 store', dx=6, dy=8)
    return d, 'The big picture. The numbers 1 to 6 are the six steps of every reconciliation run.'


def d_ledger_balances():
    head = ['Account (type)', 'After the payment', 'After the bank settles', 'After the payout']
    rows = [
        ['CLEARING:HDFC (asset)', '₹1,000', '₹0', '₹0'],
        ['SETTLEMENT_CASH (asset)', '₹0', '₹1,000', '₹20'],
        ['MERCHANT_PAYABLE (liability)', '₹980', '₹980', '₹0'],
        ['FEE_REVENUE (revenue)', '₹20', '₹20', '₹20'],
        ['Check: assets = liabilities + revenue', '1,000 = 980 + 20', '1,000 = 980 + 20', '20 = 0 + 20'],
    ]
    data = [[Paragraph('<b>%s</b>' % h, S['cellHead']) for h in head]]
    for r in rows:
        data.append([Paragraph(glyph_fix(escape(c), 'Body'), S['cell']) for c in r])
    t = Table(data, colWidths=[W * 0.37, W * 0.21, W * 0.21, W * 0.21])
    style = [('BACKGROUND', (0, 0), (-1, 0), HexColor('#1e293b')), ('GRID', (0, 0), (-1, -1), 0.5, HexColor('#cbd5e1')),
             ('BACKGROUND', (0, 1), (0, 2), HexColor('#dbeafe')), ('BACKGROUND', (0, 3), (0, 3), HexColor('#ede9fe')),
             ('BACKGROUND', (0, 4), (0, 4), HexColor('#dcfce7')), ('BACKGROUND', (0, 5), (-1, 5), HexColor('#ecfdf5')),
             ('ALIGN', (1, 1), (-1, -1), 'CENTER'), ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
             ('TOPPADDING', (0, 0), (-1, -1), 5), ('BOTTOMPADDING', (0, 0), (-1, -1), 5)]
    t.setStyle(TableStyle(style))
    return t, ("Each account's balance after each step of Riya's payment. The accounting equation (last row) "
               "holds after every step: that is the point of double-entry.")


def d_idempotency():
    d = Drawing(W, 244)
    xa, xm, xb = 62, 247, 432
    top = 216
    for x, title, kind in ((xa, 'Request A', 'api'), (xm, 'MySQL', 'mysql'), (xb, 'Request B (same key)', 'api')):
        box(d, x - 62, top, 124, 24, title, (), kind, tsize=8.4)
        d.add(Line(x, top, x, 40, strokeColor=col('#94a3b8'), strokeWidth=0.8, strokeDashArray=[3, 3]))
    d.add(Rect(xm - 4, 118, 8, 78, fillColor=col('#fdba74'), strokeColor=col('#c2410c'), strokeWidth=0.6))
    label(d, xm + 9, 137, 'row locked by A', size=6.6, anchor='start', italic=True, color='#c2410c')
    steps = [
        (xa, xm, "1. insert key 'k1' (locks that row)", '#6d28d9'),
        (xb, xm, "2. insert key 'k1' ... must WAIT", '#6d28d9'),
        (xa, xm, '3. book the payment, save the answer', '#6d28d9'),
        (xa, xm, '4. COMMIT', '#6d28d9'),
        (xm, xa, '201 Created (payment pay_..)', '#15803d'),
        (xm, xb, "5. lock released: 'duplicate key'", '#b91c1c'),
        (xb, xm, '6. read the saved answer', '#6d28d9'),
    ]
    y = 196
    for x1, x2, text, color in steps:
        arrow(d, x1, y, x2, y, text, color=color, dy=3.5, size=6.9, tcolor=INK)
        y -= 22
    box(d, 190, 4, W - 190, 30, '', ['B returns the SAME payment with "Idempotent-Replayed: true".',
                                       'Exactly one payment exists. Riya is charged once.'], 'green', lsize=7.1)
    return d, 'Two requests with the same Idempotency-Key arrive at the same moment. Only one payment is ever booked.'


def d_refund_race():
    d = Drawing(W, 204)
    pw = (W - 14) / 2
    panels = [
        ('red', 'Without a lock', [('reads refunded = ₹0', 'reads refunded = ₹0'),
                                   ('600 ≤ 1,000? yes', '600 ≤ 1,000? yes'),
                                   ('refunds ₹600', 'refunds ₹600')],
         'Total refunded: ₹1,200 on a ₹1,000 payment', '✗'),
        ('green', 'With SELECT ... FOR UPDATE', [('locks the row, reads ₹0', 'waits for the lock ...'),
                                                ('refunds ₹600, commits', 'reads ₹600 (the new value)'),
                                                ('', '600 + 600 > 1,000: rejected')],
         'Total refunded: ₹600. Never more than paid.', '✓'),
    ]
    for i, (kind, title, rows, result, mark) in enumerate(panels):
        x = i * (pw + 14)
        stroke, light = PAL[kind]
        panel(d, x, 0, pw, 204, fill=light, stroke=stroke)
        label(d, x + pw / 2, 188, title, size=9.4, bold=True, color=stroke)
        label(d, x + pw * 0.26, 170, 'Refund 1 (₹600)', size=7.4, bold=True)
        label(d, x + pw * 0.74, 170, 'Refund 2 (₹600)', size=7.4, bold=True)
        yy = 136
        for a, b in rows:
            if a:
                box(d, x + 8, yy, pw / 2 - 12, 24, '', [a], 'grey', lsize=7.0, fill='#ffffff')
            if b:
                box(d, x + pw / 2 + 4, yy, pw / 2 - 12, 24, '', [b], 'grey', lsize=7.0, fill='#ffffff')
            yy -= 34
        box(d, x + 8, 12, pw - 16, 30, '', [result], kind, lsize=7.8, fill='#ffffff')
        sym(d, x + pw - 20, 22, mark, size=12, color=stroke)
    return d, 'Two refunds of ₹600 on a ₹1,000 payment arrive together. Locking the row makes the second one wait.'


def d_mapreduce():
    d = Drawing(W, 210)
    xs, cw = [0, 127, 254, 382], 113
    heads = [('Input lines', '#334155'), ('Map: make (key, value)', '#c2410c'), ('Shuffle + sort by key', '#334155'),
             ('Reduce: one call per key', '#c2410c')]
    for x, (h, c) in zip(xs, heads):
        label(d, x + cw / 2, 194, h, size=8, bold=True, color=c)
    inputs = ['ledger: pay_1, ₹500', 'ledger: pay_2, ₹800', 'bank: pay_1, ₹500']
    mapped = ['pay_1  :  L ₹500', 'pay_2  :  L ₹800', 'pay_1  :  B ₹500']
    ys = [150, 112, 74]
    for y, a, b in zip(ys, inputs, mapped):
        box(d, xs[0], y, cw, 28, '', [a], 'bank', lsize=7.2)
        box(d, xs[1], y, cw, 28, '', [b], 'mr', lsize=7.2, mono=True)
        arrow(d, xs[0] + cw, y + 14, xs[1], y + 14)
    box(d, xs[2], 128, cw, 46, 'key pay_1', ['[ L ₹500, B ₹500 ]'], 'bank', mono=True)
    box(d, xs[2], 74, cw, 40, 'key pay_2', ['[ L ₹800 ]'], 'bank', mono=True)
    arrow(d, xs[1] + cw, 164, xs[2], 158)
    arrow(d, xs[1] + cw, 88, xs[2], 140)
    arrow(d, xs[1] + cw, 126, xs[2], 96)
    box(d, xs[3], 128, cw, 46, 'pay_1', ['both sides found:', 'MATCHED'], 'green')
    box(d, xs[3], 74, cw, 40, 'pay_2', ['no bank line yet:', 'try again in job 2'], 'red')
    arrow(d, xs[2] + cw, 151, xs[3], 151)
    arrow(d, xs[2] + cw, 94, xs[3], 94)
    box(d, 0, 6, W, 46, '', ['Records with the same key always meet in the same reduce() call.',
                             'Each key is independent, so adding more machines makes the job faster.'],
        'grey', lsize=7.6)
    return d, 'MapReduce in three steps, using job 1 as the example: a payment and its bank line meet by reference.'


def d_jobs_dag():
    d = Drawing(W, 226)
    box(d, 0, 164, 105, 42, 'Ledger payments', ['from MySQL'], 'mysql')
    box(d, 0, 100, 105, 46, 'Bank files', ['HDFC, ICICI, CARDNET'], 'bank')
    box(d, 0, 24, 105, 42, 'Ledger payouts', ['from MySQL'], 'mysql')
    box(d, 136, 98, 104, 96, 'Job 1', ['exact reference', '(reduce-side join)', 'key = payment ref'], 'mr')
    panel(d, 300, 14, 112, 206, fill='#fff7ed', stroke='#c2410c', dash=[4, 3])
    label(d, 356, 209, 'jobs 2 and 3 run at the same time', size=6.6, italic=True, color='#c2410c', max_w=106)
    box(d, 308, 138, 96, 58, 'Job 2', ['amount + date window', '(secondary sort)'], 'mr')
    box(d, 308, 26, 96, 58, 'Job 3', ['payout batches', '(combiner)'], 'mr')
    box(d, 434, 66, 61, 92, 'Results', ['matched', 'exceptions'], 'grey')
    arrow(d, 105, 185, 136, 172)
    arrow(d, 105, 123, 136, 123)
    arrow(d, 240, 174, 308, 170)
    label(d, 270, 181, 'not matched', size=6.6, color='#334155')
    arrow(d, 240, 110, 308, 68)
    label(d, 262, 76, 'bank debits', size=6.6, color='#334155')
    arrow(d, 105, 44, 308, 46, 'payouts', dy=4)
    arrow(d, 240, 132, 434, 122)
    label(d, 356, 131, 'matched + exceptions', size=6.6, color='#334155')
    arrow(d, 404, 166, 434, 146)
    arrow(d, 404, 56, 434, 80)
    return d, 'The three MapReduce jobs as a DAG: job 1 runs first, then jobs 2 and 3 run in parallel.'


def d_window():
    d = Drawing(W, 162)
    x0, x1 = 36, 486

    def xd(day):
        return x0 + (day - 1) * (x1 - x0) / 11

    axis = 96
    d.add(Rect(xd(5), 48, xd(10) - xd(5), 104, fillColor=col('#ffedd5'), strokeColor=col('#fdba74'),
               strokeWidth=0.8, strokeDashArray=[3, 2]))
    label(d, (xd(5) + xd(10)) / 2, 142, 'the window: 5 days before the bank date, up to the bank date', size=6.8,
          color='#c2410c')
    d.add(Line(x0 - 6, axis, x1 + 6, axis, strokeColor=col('#64748b'), strokeWidth=1))
    for day in range(1, 13):
        d.add(Line(xd(day), axis - 3, xd(day), axis + 3, strokeColor=col('#64748b'), strokeWidth=0.8))
        if day in (5, 10):  # these labels sit on the window's dashed edge: give them a background
            lw = stringWidth('Oct %d' % day, F['Body'], 6.2)
            d.add(Rect(xd(day) - lw / 2 - 2, axis - 14, lw + 4, 9, fillColor=col('#ffedd5'), strokeColor=None))
        label(d, xd(day), axis - 12, 'Oct %d' % day, size=6.2, color='#475569')
    label(d, 0, 118, 'ledger', anchor='start', size=7, bold=True, color='#1d4ed8')
    label(d, 0, 64, 'bank', anchor='start', size=7, bold=True, color='#334155')
    for day, name, inside in ((2, 'pay_A', False), (9, 'pay_B', True)):
        cx, cy = xd(day), 120
        d.add(Circle(cx, cy, 6, fillColor=col('#dbeafe' if inside else '#f1f5f9'),
                     strokeColor=col('#1d4ed8' if inside else '#94a3b8'), strokeWidth=1.1))
        label(d, cx, cy + 10, '%s  ₹1,234.56' % name, size=6.8, bold=inside, color=INK if inside else '#64748b')
    label(d, xd(2), 104, 'outside the window', size=6.2, italic=True, color='#64748b')
    bx = xd(10)
    d.add(Rect(bx - 6, 60, 12, 12, fillColor=col('#e2e8f0'), strokeColor=col('#334155'), strokeWidth=1.1))
    label(d, bx - 10, 52, 'bank line on Oct 10: ₹1,234.56, no reference', size=6.8, anchor='end')
    arrow(d, bx - 2, 72, xd(9) + 3, 113, color='#15803d', width=1.3)
    label(d, bx + 12, 70, 'exactly one fits:', size=6.8, bold=True, color='#15803d', anchor='start')
    label(d, bx + 12, 60, 'MATCH', size=7.4, bold=True, color='#15803d', anchor='start')
    box(d, 0, 4, W, 34, '', ['One reduce() call gets this whole group (HDFC, ₹1,234.56), already sorted by date.',
                             'If two or more payments fit inside the window: AMBIGUOUS. The engine never guesses; '
                             'a person decides.'], 'grey', lsize=7.0)
    return d, 'Job 2 for one group: same bank, same amount, and a date inside the window.'


def d_cash_fix():
    d = Drawing(W, 226)
    pw = (W - 16) / 2
    for i, title in enumerate(('Run 1: bank files up to 31 Oct', 'Run 2: bank files up to 5 Nov')):
        x = i * (pw + 16)
        panel(d, x, 54, pw, 172)
        label(d, x + pw / 2, 210, title, size=8.6, bold=True, color='#0f172a')
    x = 0
    box(d, x + 12, 158, pw - 24, 38, 'HDFC bank line: ₹2,000', ['it has no payment reference'], 'bank')
    box(d, x + 12, 110, pw - 24, 36, 'Two payments fit', ['AMBIGUOUS: never guess'], 'red')
    box(d, x + 12, 62, pw - 24, 36, 'Money parked in SUSPENSE', ['record: line is in SUSPENSE'], 'hive')
    arrow(d, x + pw / 2, 158, x + pw / 2, 146)
    arrow(d, x + pw / 2, 110, x + pw / 2, 98)
    x = pw + 16
    box(d, x + 12, 158, pw - 24, 38, 'The same bank line', ['more files: now only one payment fits'], 'bank')
    box(d, x + 12, 110, pw - 24, 36, 'MATCHED', ['record changes: SUSPENSE to CLEARING'], 'green')
    box(d, x + 12, 62, pw - 24, 36, 'Ledger entry: SUSPENSE_CLEARED',
        ['debit SUSPENSE ₹2,000, credit CLEARING:HDFC ₹2,000'], 'api')
    arrow(d, x + pw / 2, 158, x + pw / 2, 146)
    arrow(d, x + pw / 2, 110, x + pw / 2, 98)
    box(d, 0, 0, W, 44, 'The fix', [
        'Old design: one entry per bank per day. A day already booked was skipped, so ₹2,000 stayed stuck.',
        'New design: remember every bank line and book only the changes. Re-running is always safe.'],
        'teal', lsize=7.2)
    return d, 'Why cash is tracked per bank line: money parked in suspense moves to clearing once it is identified.'


def d_tie_out():
    d = Drawing(W, 156)
    lx, rx = 150, W
    d.add(Rect(lx, 104, rx - lx, 30, fillColor=col('#dbeafe'), strokeColor=col('#1d4ed8'), strokeWidth=1))
    label(d, (lx + rx) / 2, 116, 'captured from customers: ₹8,88,49,703', size=8, bold=True, color='#1d4ed8')
    label(d, lx - 8, 116, 'What the ledger expects', anchor='end', size=8, bold=True)
    split = lx + (rx - lx) * 0.55
    d.add(Rect(lx, 58, split - lx, 30, fillColor=col('#ccfbf1'), strokeColor=col('#0f766e'), strokeWidth=1))
    label(d, (lx + split) / 2, 70, 'cash the bank paid: ₹8,59,59,176', size=7.6, bold=True, color='#0f766e')
    segs = [('in transit', '#ffedd5'), ('missing', '#fee2e2'), ('in review', '#fef3c7'), ('short-paid', '#ede9fe')]
    sw = (rx - split) / 4
    for k, (name, c) in enumerate(segs):
        d.add(Rect(split + k * sw, 58, sw, 30, fillColor=col(c), strokeColor=col('#c2410c'), strokeWidth=0.8))
        label(d, split + k * sw + sw / 2, 47, name, size=6.3, color='#7c2d12')
    label(d, (split + rx) / 2, 92, 'open items: ₹28,90,527', size=7.4, bold=True, color='#c2410c')
    label(d, lx - 8, 70, 'What actually happened', anchor='end', size=8, bold=True)
    box(d, lx, 6, rx - lx, 26, '', ['Same length = every rupee explained = TIES OUT.   (HDFC, October; not to scale)'],
        'green', lsize=7.6)
    return d, 'The tie-out for one bank: captured equals cash received plus the open items, to the paisa.'


def folder(d, x, y):
    d.add(Rect(x, y, 13, 9, fillColor=col('#fde68a'), strokeColor=col('#b45309'), strokeWidth=0.6))
    d.add(Rect(x, y + 9, 6, 2.5, fillColor=col('#fde68a'), strokeColor=col('#b45309'), strokeWidth=0.6))


def d_hive_partitions():
    d = Drawing(W, 214)
    rows = [(0, 'data/warehouse/', None), (1, 'recon_matches/', None), (2, 'run_id=1/', 'skip'),
            (2, 'run_id=2/', 'read'), (1, 'bank_lines/', None), (2, 'value_date=2026-10-02/', None),
            (2, 'value_date=2026-10-03/  ... one folder per day', None)]
    y = 186
    run2_y = None
    for depth, name, state in rows:
        x = 6 + depth * 22
        if state == 'read':
            d.add(Rect(x - 4, y - 5, 150, 20, fillColor=col('#dcfce7'), strokeColor=col('#15803d'), strokeWidth=0.8))
            run2_y = y
        folder(d, x, y)
        label(d, x + 18, y + 1, name, size=7.6, anchor='start', mono=True,
              color='#94a3b8' if state == 'skip' else INK)
        if state == 'skip':
            label(d, x + 76, y + 1, 'skipped (partition pruning)', size=6.8, italic=True, anchor='start',
                  color='#94a3b8')
        y -= 22
    box(d, 300, run2_y - 24, 195, 58, 'Hive query', ['SELECT ... FROM recon_matches', 'WHERE run_id = 2'], 'hive',
        mono=True)
    arrow(d, 300, run2_y + 5, 202, run2_y + 5, 'reads only this folder', color='#15803d', dy=5, tcolor='#15803d')
    box(d, 0, 4, W, 38, '', ['A partition is just a folder. The WHERE filter tells Hive which folders to open, so a',
                             'run 2 report never reads run 1. MSCK REPAIR tells Hive when new folders appear.'],
        'grey', lsize=7.2)
    return d, 'Hive partitions are folders. Filtering on the partition column reads only the folders it needs.'


def d_cqrs():
    d = Drawing(W, 230)
    label(d, 0, 218, "Raw bank records: keep each bank's own shape", anchor='start', size=8.2, bold=True,
          color='#15803d')
    box(d, 0, 122, 130, 82, 'Bank files', ['HDFC: CSV (6 columns)', 'ICICI: pipe-separated', 'CARDNET: nested JSON'],
        'bank')
    box(d, 180, 116, 315, 92, 'bank_raw_records (one document per line)', [
        '{ _id: "CARDNET:cardnet_settlement_...:193",',
        '  raw: "<the original line>",',
        '  fields: { txn: { merchant_ref: ... }, gross: { ... } },',
        '  normalized: { reference, amount_minor, value_date } }'], 'mongo', mono=True, lsize=6.8)
    arrow(d, 130, 163, 180, 163, 'as-is', dy=5)
    label(d, 0, 98, 'Merchant statements: a read model (CQRS)', anchor='start', size=8.2, bold=True,
          color='#15803d')
    box(d, 0, 8, 130, 76, 'MySQL ledger', ['the write model:', 'payment, payout, refund', 'tables (normalised)'],
        'mysql')
    box(d, 180, 8, 190, 76, 'payout_statements', ['one document per payout:', 'summary + list of payments',
                                                 '+ refunds + bank status'], 'mongo')
    box(d, 410, 20, 85, 52, 'Merchant app', ['ONE read per', 'statement'], 'api')
    arrow(d, 130, 46, 180, 46, 'rebuilt', dy=5)
    label(d, 155, 33, 'every run', size=6.8, color='#334155')
    arrow(d, 410, 46, 370, 46, 'reads', dy=5)
    return d, "MongoDB's two jobs: raw records exactly as the banks sent them, and ready-made statements."


def d_skip_locked():
    d = Drawing(W, 214)
    tx, tw = 170, 228
    label(d, tx + tw / 2, 196, 'recon_exception: the queue, highest priority first', size=7.8, bold=True,
          color='#1d4ed8')
    rows = [('#1', 'PAYOUT_NOT_EXECUTED', 'Alice'), ('#2', 'MISSING_IN_LEDGER', 'Bob'),
            ('#3', 'MISSING_IN_LEDGER', 'Chitra'), ('#4', 'DUPLICATE_IN_BANK', None), ('#5', 'MISSING_IN_BANK', None)]
    ys = []
    y = 166
    for rid, typ, who in rows:
        locked = who is not None
        d.add(Rect(tx, y, tw, 22, fillColor=col('#fef3c7' if locked else '#ffffff'), strokeColor=col('#94a3b8'),
                   strokeWidth=0.8))
        label(d, tx + 8, y + 7.5, rid, anchor='start', size=7.4, bold=True)
        label(d, tx + 30, y + 7.5, typ, anchor='start', size=7.0, mono=True)
        label(d, tx + tw - 8, y + 7.5, ('locked by ' + who) if locked else 'OPEN', anchor='end', size=6.8,
              bold=locked, color='#b45309' if locked else '#15803d')
        ys.append(y)
        y -= 27
    for k, name in enumerate(('Alice', 'Bob', 'Chitra')):
        ay = 160 - k * 44
        box(d, 0, ay, 96, 28, name, ['claims at the same moment'], 'api', tsize=8, lsize=6.2)
        arrow(d, 96, ay + 14, tx, ys[k] + 11, color='#6d28d9')
    box(d, 410, 92, 85, 92, 'SKIP LOCKED', ['skip rows that', 'someone else holds,', 'instead of waiting', 'for them'],
        'mysql', lsize=6.8)
    box(d, 0, 4, W, 34, '', ['SELECT exception_id FROM recon_exception WHERE run_id = ? AND status = \'OPEN\' ...',
                             'ORDER BY exception_id LIMIT 1 FOR UPDATE SKIP LOCKED'], 'grey', mono=True, lsize=6.9)
    return d, 'Three analysts claim at the same moment and each gets a different item. Nobody waits.'


def d_test_layers():
    d = Drawing(W, 186)
    layers = [
        ('1  Unit tests', '27 tests on the rules: money, the bank readers, the three matchers, the ledger', 'grey'),
        ('2  Answer key (verify)', '1,072 planted problems: 0 missed, 0 false alarms', 'teal'),
        ('3  Tie-out', 'for every bank: captured - cash booked = open items, to the paisa', 'mysql'),
        ('4  Ledger audit', '9 accounting rules checked over the whole ledger', 'api'),
        ('5  Stress test', 'real concurrency: same key x50, refund race, 200 payments, queue claims', 'mr'),
    ]
    y = 150
    for title, text, kind in layers:
        stroke, light = PAL[kind]
        d.add(Rect(0, y, W, 30, rx=6, ry=6, fillColor=col(light), strokeColor=col(stroke), strokeWidth=1))
        d.add(Rect(0, y, 140, 30, rx=6, ry=6, fillColor=col(stroke), strokeColor=col(stroke), strokeWidth=1))
        label(d, 10, y + 11, title, anchor='start', size=8.4, bold=True, color='#ffffff')
        label(d, 152, y + 11, text, anchor='start', size=7.6, max_w=W - 160)
        y -= 36
    return d, 'Five independent checks. Each one catches a different kind of mistake.'


def d_docker_setup():
    d = Drawing(W, 240)
    panel(d, 0, 0, W, 240, fill='#f8fafc', stroke='#94a3b8', r=10)
    label(d, 12, 224, 'Your Mac', anchor='start', bold=True, size=9, color='#0f172a')
    box(d, 12, 92, 92, 56, '.env file', ['all the passwords', 'git never uploads it'], 'red', lsize=6.6)
    box(d, 132, 22, 118, 190, 'Java app', ['./payrecon', '', 'the command-line tool', '+ the REST API (port 8090)',
                                           '', 'Hadoop MapReduce', 'runs inside it', '("local mode")'], 'api', lsize=6.8)
    arrow(d, 104, 120, 132, 120, 'reads', dy=4)
    px = 296
    panel(d, px, 12, W - px - 10, 206, fill='#eef2ff', stroke='#6366f1', dash=[5, 3])
    label(d, px + 10, 204, 'Docker (Colima): docker compose up -d', anchor='start', bold=True, size=8, color='#4338ca')
    bx, bw = px + 12, W - px - 34
    box(d, bx, 142, bw, 50, 'MySQL 8.4', ['port 3307 on the Mac', 'data kept in the volume "mysql-data"'], 'mysql')
    box(d, bx, 84, bw, 50, 'MongoDB 7.0', ['port 27017', 'data kept in the volume "mongo-data"'], 'mongo')
    box(d, bx, 26, bw, 50, 'Hive 4.0.1 (brings its own Java 8)', ['port 10000', 'reads ./data through a shared folder'],
        'hive')
    for y, text in ((167, 'SQL'), (109, 'documents'), (51, 'HiveQL')):
        arrow(d, 250, y, bx, y)
        label(d, 273, y + 4, text, size=6.8, color='#334155')
    return d, 'The setup: one Java program on the Mac, plus three databases in Docker containers.'


def d_phases():
    d = Drawing(W, 176)
    steps = [('1', 'Foundations', 'settings, money, tables', 'grey'), ('2', 'Ledger', 'double-entry + audit', 'mysql'),
             ('3', 'Rules', 'bank readers + matchers', 'teal'), ('4', 'MapReduce', 'the 3 matching jobs', 'mr'),
             ('5', 'Recon run', 'six steps + tie-out', 'mr'), ('6', 'Test data', 'generator + verify', 'teal'),
             ('7', 'API + queue', 'idempotency, stress test', 'api'), ('8', 'Finish', 'tests, demo, documents',
                                                                        'green')]
    cw, ch = 104, 52
    gap = (W - 4 * cw) / 3
    pos = []
    for i, (num, title, sub, kind) in enumerate(steps):
        row, c = divmod(i, 4)
        x, y = c * (cw + gap), 106 - row * 92
        box(d, x, y, cw, ch, title, [sub], kind, lsize=6.8)
        stroke = PAL[kind][0]
        d.add(Circle(x + 4, y + ch - 2, 9, fillColor=col(stroke), strokeColor=col('#ffffff'), strokeWidth=1.2))
        label(d, x + 4, y + ch - 5, num, size=8, bold=True, color='#ffffff')
        pos.append((x, y))
        if c > 0:
            px, py = pos[i - 1]
            arrow(d, px + cw, py + ch / 2, x, y + ch / 2)
    x4, y4 = pos[3]
    x5, y5 = pos[4]
    elbow(d, [x4 + cw / 2, y4, x4 + cw / 2, (y4 + y5 + ch) / 2, x5 + cw / 2, (y4 + y5 + ch) / 2, x5 + cw / 2,
              y5 + ch])
    return d, 'The eight build steps. Each step was checked before the next one started.'


def d_tables():
    d = Drawing(W, 266)
    panel(d, 0, 76, 318, 190, fill='#eff6ff', stroke='#93c5fd')
    label(d, 10, 252, 'Ledger (double-entry)', anchor='start', bold=True, size=8.4, color='#1d4ed8')

    def table(x, y, name, cols, w=96):
        box(d, x, y, w, 36, name, cols, 'mysql', tsize=7.8, lsize=6.4, fill='#ffffff')

    table(10, 202, 'merchant', ['merchant_id, fee_bps'])
    table(113, 202, 'account', ['code, type, balance'])
    table(216, 202, 'journal_entry', ['entry_type, external_ref'])
    table(10, 142, 'payment', ['amount, fee, refunded'])
    table(113, 142, 'posting', ['entry, account, side, amount'])
    table(216, 142, 'refund', ['payment, amount'])
    table(10, 86, 'payout_batch', ['bank, date, total'])
    table(113, 86, 'payout', ['merchant, batch, amount'])
    arrow(d, 113, 220, 106, 220)
    arrow(d, 161, 178, 161, 202)
    arrow(d, 192, 178, 238, 202)
    arrow(d, 58, 178, 58, 202)
    elbow(d, [264, 142, 264, 132, 58, 132, 58, 142])
    arrow(d, 113, 104, 106, 104)
    label(d, 268, 100, 'arrows point to the', size=6.4, italic=True, color='#475569', anchor='middle')
    label(d, 268, 91, 'table a row belongs to', size=6.4, italic=True, color='#475569', anchor='middle')
    panel(d, 0, 0, 318, 68, fill='#fef2f2', stroke='#fca5a5')
    label(d, 10, 54, 'Safety', anchor='start', bold=True, size=8.4, color='#b91c1c')
    box(d, 10, 8, 146, 38, 'idempotency_key', ['request key + the saved answer'], 'red', tsize=7.8, lsize=6.4,
        fill='#ffffff')
    box(d, 164, 8, 146, 38, 'cash_application', ['each bank line: CLEARING or SUSPENSE'], 'red', tsize=7.8,
        lsize=6.4, fill='#ffffff')
    panel(d, 328, 0, 167, 266, fill='#f0fdf4', stroke='#86efac')
    label(d, 338, 252, 'Reconciliation', anchor='start', bold=True, size=8.4, color='#15803d')

    def rtable(y, name, cols):
        box(d, 342, y, 140, 34, name, cols, 'green', tsize=7.8, lsize=6.4, fill='#ffffff')

    rtable(204, 'recon_run', ['one row per run: counts, time'])
    rtable(156, 'recon_pass_stat', ['matches per pass'])
    rtable(108, 'recon_step_timing', ['seconds per step'])
    rtable(60, 'recon_exception', ['the analysts\' work queue'])
    rtable(12, 'exception_event', ['who did what, and when'])
    arrow(d, 412, 190, 412, 204)
    elbow(d, [482, 125, 489, 125, 489, 221, 482, 221])
    elbow(d, [342, 77, 335, 77, 335, 221, 342, 221])
    arrow(d, 412, 46, 412, 60)
    return d, 'The 15 MySQL tables in three groups. Arrows point from a row to the row it belongs to (foreign keys).'


DIAGRAMS = {
    'money_flow': d_money_flow, 'architecture': d_architecture, 'ledger_balances': d_ledger_balances,
    'idempotency': d_idempotency, 'refund_race': d_refund_race, 'mapreduce': d_mapreduce, 'jobs_dag': d_jobs_dag,
    'window': d_window, 'cash_fix': d_cash_fix, 'tie_out': d_tie_out, 'hive_partitions': d_hive_partitions,
    'cqrs': d_cqrs, 'skip_locked': d_skip_locked, 'test_layers': d_test_layers, 'docker_setup': d_docker_setup,
    'phases': d_phases, 'tables': d_tables,
}


# ---------------------------------------------------------------------------------------------- document

class Doc(BaseDocTemplate):
    def __init__(self, filename, meta):
        super().__init__(filename, pagesize=A4, leftMargin=MARGIN_X, rightMargin=MARGIN_X, topMargin=58,
                         bottomMargin=54, title=meta['title'], author='PayRecon', subject=meta['subtitle'],
                         creator='tools/make_pdfs.py')
        self.meta = meta
        cover = Frame(MARGIN_X, 54, W, PAGE_H - 330 - 54, id='cover')
        body = Frame(MARGIN_X, 54, W, PAGE_H - 58 - 54, id='body')
        self.addPageTemplates([PageTemplate(id='cover', frames=[cover], onPage=self.draw_cover),
                               PageTemplate(id='body', frames=[body], onPage=self.draw_body)])

    def afterFlowable(self, flowable):
        if isinstance(flowable, Paragraph) and flowable.style.name in ('H2', 'H3'):
            level = 0 if flowable.style.name == 'H2' else 1
            text = flowable.getPlainText()
            key = 'h-%d-%d' % (self.page, abs(hash(text)))
            self.canv.bookmarkPage(key)
            self.canv.addOutlineEntry(text, key, level=level, closed=level > 0)
            self.notify('TOCEntry', (level, text, self.page, key))

    def draw_cover(self, canvas, doc):
        canvas.saveState()
        canvas.setFillColor(HexColor('#0f172a'))
        canvas.rect(0, PAGE_H - 300, PAGE_W, 300, stroke=0, fill=1)
        canvas.setFillColor(HexColor('#2563eb'))
        canvas.rect(0, PAGE_H - 306, PAGE_W, 6, stroke=0, fill=1)
        canvas.setFillColor(HexColor('#93c5fd'))
        canvas.setFont(F['BodyBold'], 11)
        canvas.drawString(MARGIN_X, PAGE_H - 92, 'PAYRECON')
        canvas.setFillColor(colors.white)
        canvas.setFont(F['BodyBold'], 34)
        canvas.drawString(MARGIN_X, PAGE_H - 140, self.meta['title'])
        canvas.setFillColor(HexColor('#cbd5e1'))
        canvas.setFont(F['Body'], 13)
        y = PAGE_H - 172
        for line in self.meta['subtitle_lines']:
            canvas.drawString(MARGIN_X, y, line)
            y -= 18
        canvas.setFillColor(HexColor('#94a3b8'))
        canvas.setFont(F['Body'], 9)
        canvas.drawString(MARGIN_X, PAGE_H - 270,
                          'Payment ledger + bank reconciliation   ·   Java 17   ·   MySQL   ·   '
                          'Hadoop MapReduce   ·   Hive   ·   MongoDB')
        canvas.restoreState()

    def draw_body(self, canvas, doc):
        canvas.saveState()
        canvas.setStrokeColor(HexColor('#e2e8f0'))
        canvas.setLineWidth(0.6)
        canvas.line(MARGIN_X, PAGE_H - 40, PAGE_W - MARGIN_X, PAGE_H - 40)
        canvas.line(MARGIN_X, 40, PAGE_W - MARGIN_X, 40)
        canvas.setFont(F['Body'], 8)
        canvas.setFillColor(HexColor('#64748b'))
        canvas.drawString(MARGIN_X, PAGE_H - 33, 'PayRecon  ·  ' + self.meta['title'])
        canvas.drawRightString(PAGE_W - MARGIN_X, PAGE_H - 33, 'Page %d' % doc.page)
        canvas.drawString(MARGIN_X, 28, self.meta['footer'])
        canvas.restoreState()


def facts_table(rows):
    data = [[Paragraph('<b>%s</b>' % escape(k), S['cell']), Paragraph(inline(v), S['cell'])] for k, v in rows]
    t = Table(data, colWidths=[W * 0.2, W * 0.8])
    t.setStyle(TableStyle([('BACKGROUND', (0, 0), (0, -1), HexColor('#f1f5f9')),
                           ('GRID', (0, 0), (-1, -1), 0.5, HexColor('#cbd5e1')), ('VALIGN', (0, 0), (-1, -1), 'TOP'),
                           ('TOPPADDING', (0, 0), (-1, -1), 5), ('BOTTOMPADDING', (0, 0), (-1, -1), 5),
                           ('LEFTPADDING', (0, 0), (-1, -1), 7)]))
    return t


def build(meta):
    with open(os.path.join(ROOT, meta['src']), encoding='utf-8') as f:
        blocks = parse_markdown(f.read())
    first_h2 = next(i for i, b in enumerate(blocks) if b[0] == 'h' and b[1] == 2)
    intro, body = blocks[:first_h2], blocks[first_h2:]
    counter = [0]
    story = [Paragraph('At a glance', S['coverH']), facts_table(meta['facts']), Spacer(1, 14)]
    story += render(intro, counter)
    story += [NextPageTemplate('body'), PageBreak()]
    toc = TableOfContents()
    toc.levelStyles = [S['toc0'], S['toc1']]
    toc.tableStyle = TableStyle([('VALIGN', (0, 0), (-1, -1), 'TOP'), ('LEFTPADDING', (0, 0), (-1, -1), 0),
                                 ('RIGHTPADDING', (0, 0), (-1, -1), 0), ('TOPPADDING', (0, 0), (-1, -1), 1.2),
                                 ('BOTTOMPADDING', (0, 0), (-1, -1), 1.2)])
    story += [Paragraph('Contents', S['tocTitle']), toc, PageBreak()]
    story += render(body, counter)
    out = os.path.join(ROOT, meta['out'])
    doc = Doc(out, meta)
    doc.multiBuild(story)
    return out, counter[0]


DOCS = [
    dict(src='docs/PROJECT_EXPLAINED.md', out='docs/PayRecon-Project-Explained.pdf', title='Project Explained',
         subtitle='How a payment ledger and bank reconciliation system works, in plain English',
         subtitle_lines=['How a payment ledger and bank reconciliation system works,',
                         'explained in plain English, with pictures.'],
         footer='Generated from docs/PROJECT_EXPLAINED.md',
         facts=[('What it is', 'A working miniature of the ledger and reconciliation systems that payment companies '
                               'such as Stripe and Razorpay run'),
                ('Built with', 'Java 17, MySQL 8.4, Hadoop MapReduce 3.3, Hive 4.0, MongoDB 7.0, Docker'),
                ('Test month', '1,000 shops, 2,00,000 payments (₹25.1 crore), 105 bank files in three formats'),
                ('Results', '99.83% matched automatically; every planted problem found; every bank ties out to '
                            'the paisa')]),
    dict(src='docs/BUILD_PLAN.md', out='docs/PayRecon-Build-Plan.pdf', title='Build Plan',
         subtitle='How PayRecon was planned, built and checked',
         subtitle_lines=['How PayRecon was planned, built and checked,', 'step by step, in plain English.'],
         footer='Generated from docs/BUILD_PLAN.md',
         facts=[('Goal', 'An SDE-interview project on MapReduce, Hive, MongoDB and MySQL. No Pig, no machine '
                         'learning, and every tool has a real job'),
                ('Status', 'Complete: built, tested and documented'),
                ('Checks', '27 unit tests, the answer-key check, the tie-out, a 9-rule ledger audit and a stress '
                           'test: all pass'),
                ('Size', 'About 6,300 lines of Java, 7 Hive scripts and 15 MySQL tables')]),
]


def main():
    register_fonts()
    make_styles()
    for meta in DOCS:
        out, figures = build(meta)
        print('wrote %s (%d figures)' % (os.path.relpath(out, ROOT), figures))


if __name__ == '__main__':
    main()
