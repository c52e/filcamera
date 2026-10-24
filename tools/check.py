"""Check static HTML metadata, semantic structure and local links."""
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit, unquote

ROOT = Path(__file__).resolve().parents[1]
class Document(HTMLParser):
    def __init__(self, file):
        super().__init__(); self.file=file; self.ids=set(); self.links=[]; self.h1=0; self.lang=None; self.title=False; self.description=False; self.alternates=set()
    def handle_starttag(self, tag, attributes):
        a=dict(attributes)
        if 'id' in a:
            assert a['id'] not in self.ids, f'Duplicate ID: {self.file}: {a["id"]}'
            self.ids.add(a['id'])
        if tag=='h1': self.h1+=1
        if tag=='html': self.lang=a.get('lang')
        if tag=='title': self.title=True
        if tag=='meta' and a.get('name')=='description': self.description=bool(a.get('content'))
        if tag=='link' and a.get('rel')=='alternate': self.alternates.add(a.get('hreflang'))
        if tag in ('a','link','script','img'):
            target=a.get('href',a.get('src'))
            if target: self.links.append(target)

def check():
    docs={}
    for file in [ROOT/route/'index.html' for route in ('', 'zh', 'zh-hant', 'ja')]:
        doc=Document(file);doc.feed(file.read_text(encoding='utf-8-sig'));docs[file]=doc
        assert doc.h1==1 and doc.title and doc.description and doc.lang
        assert {'en','zh-CN','zh-Hant','ja','x-default'} <= doc.alternates
    for file,doc in docs.items():
        for target in doc.links:
            url=urlsplit(target)
            if url.scheme or url.netloc: continue
            assert not url.path.startswith('/'), f'Root-absolute local URL: {target}'
            path=(file.parent/unquote(url.path)).resolve() if url.path else file
            if path.is_dir(): path=path/'index.html'
            assert path.is_file(), f'Missing: {file}: {target}'
            assert path.is_relative_to(ROOT), f'Escaping URL: {target}'
            if url.fragment and path in docs: assert url.fragment in docs[path].ids, target
    assert (ROOT/'.nojekyll').is_file()
    print('PASS: EN/ZH-Hans/ZH-Hant/JA metadata, semantic headings, language alternatives, local resources and anchor links')
if __name__=='__main__': check()
