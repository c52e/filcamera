"""Preview the static site under its GitHub Pages project path."""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit
import argparse

ROOT = Path(__file__).resolve().parents[1]
BASE = '/filcamera/'

class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        path = urlsplit(self.path).path
        if path in ('/', '/filcamera'):
            self.send_response(302)
            self.send_header('Location', BASE)
            self.end_headers()
        elif not path.startswith(BASE):
            self.send_error(404)
        else:
            super().do_GET()

    def translate_path(self, path):
        relative = unquote(urlsplit(path).path)[len(BASE):]
        parts = Path(relative).parts
        if any(part.startswith('.') for part in parts) or Path(relative).is_absolute():
            return str(ROOT / 'not-found')
        file = (ROOT / relative).resolve()
        return str(file if file.is_relative_to(ROOT) else ROOT / 'not-found')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=4173)
    args = parser.parse_args()
    print(f'Preview: http://127.0.0.1:{args.port}{BASE}', flush=True)
    ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()
