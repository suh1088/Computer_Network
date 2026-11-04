"""Rebuild the PDF and comparison text from the Markdown without changing source-page images."""
from pathlib import Path
import re,html,io,base64,json
from PIL import Image as PILImage
from reportlab.platypus import SimpleDocTemplate,Paragraph,Image,Spacer,PageBreak
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib import colors
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from pypdf import PdfReader
OUT=Path(__file__).resolve().parents[1]
md=(OUT/'FEMU_한국어_완역.md').read_text(encoding='utf-8')
title=md.splitlines()[0][2:]
oldhtml=(OUT/'FEMU_원문대조_한국어.html').read_text(encoding='utf-8')

def parse(s):
    pages=[];parts=re.split(r'<!-- page: (\d+) -->',s)
    for i in range(1,len(parts),2):
        n=int(parts[i]);blocks=[];pc=0
        for raw in re.split(r'\n\s*\n',parts[i+1].strip()):
            if raw.startswith('#'):
                level=len(raw)-len(raw.lstrip('#'));blocks.append(dict(type='heading',level=level,text=raw[level:].strip()))
            elif raw.startswith('> '):blocks.append(dict(type='note',text=raw[2:]))
            elif raw.startswith('!['):
                m=re.match(r'!\[([^]]+)\]\(([^)]+)\)',raw);blocks.append(dict(type='figure',alt=m[1],file=m[2]))
            else:
                pc+=1;blocks.append(dict(type='p',text=raw,label=f'¶{n+81 if n>1 else "표지"}-{pc:02}'))
        pages.append(dict(n=n,print=n+81 if n>1 else None,blocks=blocks))
    return pages
pages=parse(md)
def markup(s):
    s=html.escape(s);s=re.sub(r'\*\*(.*?)\*\*',r'<b>\1</b>',s)
    for key in ['freeOfChannel1','freeOfPlane2','transfer','endio','entry','erase','write','read','free','now']:
        s=s.replace('T_'+key,'T<sub>'+key+'</sub>')
    return s.replace('Lat_femu','Lat<sub>femu</sub>').replace('Lat_oc','Lat<sub>oc</sub>')
def webmarkup(s):
    s=markup(s).replace('<b>','<strong>').replace('</b>','</strong>')
    return re.sub(r'(https?://[^\s]+)',lambda m:'<a href="'+m[1].rstrip('.,')+'" target="_blank" rel="noopener">'+m[1]+'</a>',s)
def image_uri(fp):
    im=PILImage.open(fp).convert('RGB');buf=io.BytesIO();im.save(buf,format='JPEG',quality=90,optimize=True)
    return 'data:image/jpeg;base64,'+base64.b64encode(buf.getvalue()).decode()

matches=list(re.finditer(r'(<div class="translation-scroll">)(.*?)(</div></article>)',oldhtml,re.S));assert len(matches)==9
newhtml=oldhtml
for match,page in reversed(list(zip(matches,pages))):
    contents=['<h1>'+html.escape(title)+'</h1>'] if page['n']==1 else []
    for j,b in enumerate(page['blocks']):
        if b['type']=='heading':
            tag=f'h{min(4,b["level"])}';contents.append(f'<{tag} id="h-{page["n"]}-{j}">'+webmarkup(b['text'])+f'</{tag}>')
        elif b['type']=='note':contents.append('<aside class="note">'+webmarkup(b['text'])+'</aside>')
        elif b['type']=='figure':contents.append('<figure><img loading="lazy" src="'+image_uri(OUT/b['file'])+'" alt="'+b['alt']+'"></figure>')
        else:contents.append('<p id="'+b['label']+'"><span class="pid">'+b['label']+'</span>'+webmarkup(b['text'])+'</p>')
    newhtml=newhtml[:match.start(2)]+''.join(contents)+newhtml[match.end(2):]
assert re.findall(r'<img class="source"[^>]+>',oldhtml)==re.findall(r'<img class="source"[^>]+>',newhtml)
assert oldhtml[oldhtml.index('<script>'):]==newhtml[newhtml.index('<script>'):]

pdfmetrics.registerFont(TTFont('Malgun',r'C:\Windows\Fonts\malgun.ttf'))
pdfmetrics.registerFont(TTFont('MalgunBold',r'C:\Windows\Fonts\malgunbd.ttf'))
pdfmetrics.registerFont(TTFont('MathFallback',r'C:\Windows\Fonts\seguisym.ttf'))
pdfmetrics.registerFontFamily('Malgun',normal='Malgun',bold='MalgunBold',italic='Malgun',boldItalic='MalgunBold')
INK=colors.HexColor('#1b2a36');BLUE=colors.HexColor('#194c75')
styles={
'title':ParagraphStyle('title',fontName='MalgunBold',fontSize=22,leading=32,textColor=INK,wordWrap='CJK',spaceAfter=17),
'h2':ParagraphStyle('h2',fontName='MalgunBold',fontSize=15,leading=23,textColor=BLUE,wordWrap='CJK',spaceBefore=17,spaceAfter=11,keepWithNext=True),
'h3':ParagraphStyle('h3',fontName='MalgunBold',fontSize=12.5,leading=20,textColor=BLUE,wordWrap='CJK',spaceBefore=13,spaceAfter=9,keepWithNext=True),
'h4':ParagraphStyle('h4',fontName='MalgunBold',fontSize=11,leading=18,textColor=BLUE,wordWrap='CJK',spaceBefore=12,spaceAfter=8,keepWithNext=True),
'p':ParagraphStyle('p',fontName='Malgun',fontSize=9.8,leading=15.4,textColor=INK,wordWrap='CJK',spaceAfter=6.4,allowWidows=0,allowOrphans=0,splitLongWords=False),
'caption':ParagraphStyle('caption',fontName='Malgun',fontSize=9.2,leading=14.5,textColor=INK,wordWrap='CJK',spaceAfter=8,allowWidows=0,allowOrphans=0,splitLongWords=False),
'note':ParagraphStyle('note',fontName='Malgun',fontSize=9,leading=15,textColor=colors.HexColor('#12695c'),wordWrap='CJK',spaceBefore=5,spaceAfter=12,backColor=colors.HexColor('#edf7f2'),borderPadding=9,borderWidth=.5,borderColor=colors.HexColor('#b7dace'),allowWidows=0,allowOrphans=0),
'band':ParagraphStyle('band',fontName='MalgunBold',fontSize=10,leading=16,textColor=colors.white,backColor=BLUE,borderPadding=9,spaceAfter=20),
}
class Heading(Paragraph):
    def __init__(self,text,style,key,level):super().__init__(text,style);self.key=key;self.outline_level=level
class PaperDoc(SimpleDocTemplate):
    def afterFlowable(self,flow):
        if isinstance(flow,Heading):self.canv.bookmarkPage(flow.key);self.canv.addOutlineEntry(re.sub('<[^>]+>','',flow.text),flow.key,level=flow.outline_level,closed=False)
def pdfmarkup(s):
    s=markup(s)
    for c in ['−','≈']:s=s.replace(c,'<font name="MathFallback">'+c+'</font>')
    return s
def furniture(c,d):
    c.saveState();w,h=d.pagesize;c.setStrokeColor(colors.HexColor('#d3dde5'));c.setLineWidth(.5);c.line(46,h-38,w-46,h-38);c.line(46,39,w-46,39)
    c.setFont('Malgun',8);c.setFillColor(colors.HexColor('#657584'));c.drawString(46,h-29,'FEMU | 한국어 완역 · 핵심 용어 영어 표기');c.drawString(46,25,'FAST 2018 · USENIX · 원문 인쇄 쪽 83-90');c.drawRightString(w-46,25,str(d.page));c.restoreState()
story=[]
for page in pages:
    if story:story.append(PageBreak())
    n=page['n'];ref=f'원문 PDF {n}/9쪽'+(f'  |  인쇄 {page["print"]}쪽' if n>1 else '  |  표지');story.append(Heading(ref,styles['band'],f'original-{n}',0))
    if n==1:story.append(Paragraph(pdfmarkup(title),styles['title']))
    for j,b in enumerate(page['blocks']):
        if b['type']=='heading':story.append(Heading(pdfmarkup(b['text']),styles[f'h{min(4,max(2,b["level"]))}'],f'h-{n}-{j}',1))
        elif b['type']=='note':story.append(Paragraph(pdfmarkup(b['text']),styles['note']))
        elif b['type']=='figure':
            im=PILImage.open(OUT/b['file']);iw,ih=im.size;dw=min(470,iw/1.4);dh=dw*ih/iw
            if dh>200:dw*=200/dh;dh=200
            fig=Image(str(OUT/b['file']),width=dw,height=dh,hAlign='CENTER');fig.keepWithNext=True;sp=Spacer(1,8);sp.keepWithNext=True;story.extend([fig,sp])
        else:
            st=styles['caption'] if b['text'].startswith('**그림 ') else styles['p'];story.append(Paragraph(f'<font size="8" color="#426a8b">{b["label"]}</font>  '+pdfmarkup(b['text']),st))
pdfpath=OUT/'FEMU_한국어_완역.pdf'
doc=PaperDoc(str(pdfpath),pagesize=(595.276,841.89),rightMargin=46,leftMargin=46,topMargin=56,bottomMargin=55,title=title,author='Korean translation of Huaicheng Li et al. (FAST 2018)',pageCompression=1)
doc.build(story,onFirstPage=furniture,onLaterPages=furniture)
(OUT/'FEMU_원문대조_한국어.html').write_text(newhtml,encoding='utf-8')

numbered=[md.split('<!-- page: 1 -->')[0].rstrip()]
for page in pages:
    numbered.append(f'<!-- page: {page["n"]} -->')
    for b in page['blocks']:
        if b['type']=='heading':numbered.append('#'*b['level']+' '+b['text'])
        elif b['type']=='note':numbered.append('> '+b['text'])
        elif b['type']=='figure':numbered.append(f'![{b["alt"]}]({b["file"]})')
        else:numbered.append(f'**{b["label"]}**  \n'+b['text'])
(OUT/'FEMU_한국어_완역_단락표시.md').write_text('\n\n'.join(numbered)+'\n',encoding='utf-8')
text='\n'.join(p.extract_text() for p in PdfReader(pdfpath).pages)
assert [int(x) for x in re.findall(r'^\[(\d+)\]',md,re.M)]==list(range(1,48))
labels=[b['label'] for p in pages for b in p['blocks'] if b['type']=='p']
assert len(labels)==121 and len(set(labels))==121
assert all(label in text for label in labels)
assert all('['+str(n)+']' in text for n in range(1,48))
fonts=[pdfmetrics.getFont(x).face.charToGlyph for x in ['Malgun','MathFallback']]
missing=[c for c in set(md) if ord(c)>127 and not any(ord(c) in f for f in fonts)];assert not missing,missing
print(json.dumps({'pdf_pages':len(PdfReader(pdfpath).pages),'original_pages':len(pages),'paragraphs':len(labels),'figures':sum(b['type']=='figure' for p in pages for b in p['blocks']),'references':47,'missing_glyphs':missing},ensure_ascii=False))
