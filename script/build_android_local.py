#!/usr/bin/env python3
"""Build with the user-approved existing BUCC Android release key, without logging secrets."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

EXPECTED_CERT = 'd2a24530ad320f752bfab2b9235bc68e185003ea2d9908f404c106754c8db176'


def command(args, *, env=None):
    result = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('Command failed: ' + str(args[0]) + ' (exit ' + str(result.returncode) + ')')
    return result.stdout


def keychain(service, *, required=True):
    result = subprocess.run(['security', 'find-generic-password', '-s', service, '-w'], capture_output=True, text=True)
    if result.returncode:
        if required:
            raise RuntimeError('Missing or inaccessible local Keychain item: ' + service)
        return None
    return result.stdout.removesuffix('\n')


def clean_source():
    if command(['git', 'status', '--porcelain']).strip():
        raise RuntimeError('Commit source changes before building an identified artifact')
    return command(['git', 'rev-parse', 'HEAD']).strip(), command(['git', 'rev-parse', 'HEAD^{tree}']).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('output', type=Path, help='New output directory')
    args = parser.parse_args()
    sha, tree = clean_source()
    command([sys.executable, '.github/scripts/version_sync.py', '--check'])
    if args.output.exists():
        raise RuntimeError('Refusing to overwrite output directory')
    key = Path.home() / 'Library/Application Support/BUCC/Signing/BUCC-android-release.jks'
    if not key.is_file():
        raise RuntimeError('User-approved existing BUCC release keystore is missing')
    store_password = keychain('app.bucc.android.release')
    key_password = store_password
    env = os.environ.copy()
    java_home = Path(env.get('JAVA_HOME', ''))
    keytool = java_home / 'bin/keytool'
    if not keytool.is_file():
        raise RuntimeError('JAVA_HOME must point to the local JDK 21')
    env['BUCC_ANDROID_STORE_PASSWORD'] = store_password
    certificate = command([str(keytool), '-J-Duser.language=en', '-list', '-v', '-keystore', str(key),
                           '-alias', 'bucc-release', '-storepass:env', 'BUCC_ANDROID_STORE_PASSWORD'], env=env)
    match = re.search(r'SHA256:\s*([A-Fa-f0-9:]+)', certificate)
    if not match or match.group(1).replace(':', '').lower() != EXPECTED_CERT:
        raise RuntimeError('Existing keystore certificate differs from the user-approved BUCC identity')
    env.pop('BUCC_ANDROID_STORE_PASSWORD')
    env.update(SIGNING_KEY=str(key), SIGNING_STORE_PASSWORD=store_password,
               SIGNING_KEY_ALIAS='bucc-release', SIGNING_KEY_PASSWORD=key_password)
    args.output.mkdir(parents=True)
    result = subprocess.run([str(ROOT / 'gradlew'), ':androidApp:assembleRelease', '--no-configuration-cache',
                             '--no-daemon', '--max-workers=2', '--console=plain'], cwd=ROOT, env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    log = result.stdout
    for secret in (store_password, key_password):
        if secret:
            log = log.replace(secret, '[REDACTED]')
    (args.output / 'build.log').write_text(log)
    if result.returncode:
        raise RuntimeError('Android Release build failed; see sanitized build.log')
    if clean_source() != (sha, tree):
        raise RuntimeError('Source changed during build; artifact is not accepted')
    apk = ROOT / 'androidApp/build/outputs/apk/release/androidApp-release.apk'
    sdk = Path(env.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
    signer = sdk / 'build-tools/36.0.0/apksigner'
    verification = command([str(signer), 'verify', '--verbose', '--print-certs', str(apk)], env=env)
    digest = re.search(r'Signer #1 certificate SHA-256 digest:\s*([0-9a-f]+)', verification)
    if not digest or digest.group(1) != EXPECTED_CERT:
        raise RuntimeError('Release APK certificate verification failed')
    properties = dict(line.split('=', 1) for line in (ROOT / 'gradle.properties').read_text().splitlines()
                      if '=' in line and not line.startswith('#'))
    version = properties['project.version']
    artifact = args.output / f'BUCC-Android-v{version}.apk'
    shutil.copy2(apk, artifact)
    metadata = dict(platform='android', file=artifact.name, version=version,
                    version_code=int(properties['project.version.code']), source_sha=sha, source_tree=tree,
                    sha256=hashlib.sha256(artifact.read_bytes()).hexdigest(), signing='release',
                    signing_fingerprint=EXPECTED_CERT)
    (args.output / 'metadata-android.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print('Android Release APK signed and verified with the existing release identity: ' + str(artifact))


ROOT = Path(__file__).resolve().parents[1]
if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError) as error:
        print('Android build blocked: ' + str(error), file=sys.stderr)
        sys.exit(1)
