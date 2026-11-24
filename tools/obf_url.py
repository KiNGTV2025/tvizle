#!/usr/bin/env python3
"""MainActivity.sdeobf() ile çözülebilen şifreli metin üretir.

Kullanım:  python3 tools/obf_url.py https://yeni-worker.hesap.workers.dev
Çıktıyı MainActivity.kt içindeki EXTRA_WORKER_URLS listesine sdeobf("...") olarak ekle.
Gerekli: pip install cryptography
"""
import base64, hashlib, os, sys
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

P = [[2,107,150,153,63,36,85,248],[89,141,206,157,242,101,75,108],
     [137,42,164,216,187,239,55,95],[159,205,163,170,157,218,218,111]]
KEY = hashlib.sha256(bytes((v ^ 0x7E) & 0xFF for p in P for v in p)).digest()

for text in sys.argv[1:]:
    iv = os.urandom(12)
    b64 = base64.b64encode(iv + AESGCM(KEY).encrypt(iv, text.encode(), None)).decode()
    print(f'sdeobf("{b64}")   // {text}')
