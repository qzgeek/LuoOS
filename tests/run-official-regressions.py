#!/usr/bin/env python3
"""构建后执行隔离Java回归；只使用临时SQLite/本地HTTP，不加载线上凭据。"""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
jar = root / 'folia/versions/26.1.2/build/libs/luoos-folia-mc26.1.2-0.10.jar'
if not jar.exists():
    raise SystemExit('请先构建 :folia:26.1.2:shadowJar')
cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
modules = ['com.google.code.gson/gson', 'dev.folia/folia-api', 'com.google.guava/guava',
           'com.google.guava/failureaccess', 'org.slf4j/slf4j-api', 'org.jetbrains/annotations',
           'net.kyori/adventure-api', 'net.kyori/adventure-key', 'net.kyori/examination-api',
           'net.kyori/examination-string', 'net.md-5/bungeecord-chat', 'org.yaml/snakeyaml',
           'org.joml/joml', 'org.jspecify/jspecify']
deps = []
for module in modules:
    files = list((cache / module).glob('*/*/*.jar'))
    if files:
        deps.append(str(max(files, key=lambda f: f.stat().st_mtime)))
cp = os.pathsep.join([str(jar), *deps])
classes = ['OfficialBindingRegression', 'heos.folia.bot.OfficialMediaRegression', 'heos.folia.bot.CommandParityRegression', 'heos.folia.bot.OfficialAdaptRegression']
sources = [str(root / 'tests' / (c.rsplit('.', 1)[-1] + '.java')) for c in classes]
with tempfile.TemporaryDirectory(prefix='luoos-regression-classes-') as out:
    subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', cp, '-d', out, *sources], check=True)
    for cls in classes:
        subprocess.run(['java', '-Djava.awt.headless=true', '-cp', out + os.pathsep + cp, cls], check=True, timeout=90)
