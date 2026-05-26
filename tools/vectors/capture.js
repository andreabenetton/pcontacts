// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors
//
// Captures @protontech/crypto test vectors that the Kotlin port in
// :core:crypto verifies itself against. Plan §17 task 9.
//
// Reimplements Proton's computeKeyPassword / hashPassword logic using
// the same underlying bcryptjs library, avoiding the TS-only entry
// point of @protontech/crypto v2+ which can't run directly in Node.
//
// Algorithm sources (verified [V]):
//   @protontech/crypto/src/srp/keys.ts        — computeKeyPassword
//   @protontech/crypto/src/srp/passwords.ts    — hashPassword / expandHash
//   @protontech/crypto/src/srp/constants.ts    — BCRYPT_PREFIX = "$2y$10$"
//   @protontech/crypto/src/utils.ts            — binaryStringToUint8Array
//
// Outputs a single JSON file (default:
// ../../core/crypto/src/test/resources/proton-crypto-vectors.json)
// that the Kotlin-side CapturedVectorsTest consumes via the classpath.
//
// Run once when @protontech/crypto changes:
//   cd tools/vectors && npm install && node capture.js

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { hash as bcryptHash, encodeBase64 as bcryptEncodeBase64 } from 'bcryptjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const BCRYPT_PREFIX = '$2y$10$';

// ---------------------------------------------------------------------------
// computeKeyPassword — matches @protontech/crypto/src/srp/keys.ts exactly.
// ---------------------------------------------------------------------------
async function computeKeyPassword(password, saltB64) {
  if (!password || !saltB64 || saltB64.length !== 24) {
    throw new Error(`Invalid inputs: password="${password}", saltB64="${saltB64}"`);
  }
  const saltBytes = Uint8Array.from(Buffer.from(saltB64, 'base64'));
  const bcryptSalt = bcryptEncodeBase64(saltBytes, 16);
  const fullHash = await bcryptHash(password, BCRYPT_PREFIX + bcryptSalt);
  return fullHash.slice(29);
}

// ---------------------------------------------------------------------------
// hashPassword (version 4) — matches go-srp hashPasswordVersion3.
//
// Steps:
//   1. rawSalt = base64Decode(saltB64), saltWithSuffix = rawSalt || "proton"
//   2. bcryptSalt = bcryptEncodeBase64(saltWithSuffix, 16)
//   3. unexpandedHash = bcrypt(password, "$2y$10$" + bcryptSalt)
//   4. hashBytes = charCodeAt bytes of unexpandedHash
//   5. concat = hashBytes || modulusBytes
//   6. result = SHA512(concat||0x00) || SHA512(concat||0x01)
//              || SHA512(concat||0x02) || SHA512(concat||0x03)
// ---------------------------------------------------------------------------

function binaryStringToUint8Array(str) {
  const result = new Uint8Array(str.length);
  for (let i = 0; i < str.length; i++) {
    result[i] = str.charCodeAt(i);
  }
  return result;
}

function expandHash(input) {
  const parts = [];
  for (let i = 0; i < 4; i++) {
    const h = crypto.createHash('sha512');
    h.update(input);
    h.update(Uint8Array.from([i]));
    parts.push(h.digest());
  }
  return Buffer.concat(parts);
}

async function hashPasswordV4(password, saltB64, modulusBytes) {
  // Step 1: decode base64 salt to raw bytes, append "proton" (go-srp hashPasswordVersion3)
  const rawSalt = Buffer.from(saltB64, 'base64');
  const saltWithSuffix = Buffer.concat([rawSalt, Buffer.from('proton', 'ascii')]);
  // Step 2: bcrypt-encode the first 16 of those bytes
  const bcryptSalt = bcryptEncodeBase64(saltWithSuffix, 16);
  // Step 3: bcrypt
  const unexpandedHash = await bcryptHash(password, BCRYPT_PREFIX + bcryptSalt);
  // Step 4: convert hash string chars to bytes
  const hashBytes = binaryStringToUint8Array(unexpandedHash);
  // Step 5: concatenate with modulus
  const concat = Buffer.concat([hashBytes, modulusBytes]);
  // Step 6: expand
  return expandHash(concat);
}

// ---------------------------------------------------------------------------
// Vector inputs
// ---------------------------------------------------------------------------

const KEY_PASSWORD_INPUTS = [
  { label: 'ascii-short',  password: 'pass',                         saltB64: 'AAECAwQFBgcICQoLDA0ODw==' },
  { label: 'ascii-long',   password: 'correct horse battery staple', saltB64: 'qrvM3e7/ABEiM0RVZneImQ==' },
  { label: 'utf8-unicode', password: 'pässwörd-Ω',                  saltB64: '3q2+78r+/s7wDbrqEjRWeA==' },
];

// A 256-byte (2048-bit) modulus for SRP vector capture. This is the
// RFC 3526 §2 1024-bit MODP prime zero-padded to 256 bytes — NOT a
// real Proton modulus, but exercises the full code path.
const TEST_MODULUS_HEX =
  '00000000000000000000000000000000' +
  '00000000000000000000000000000000' +
  '00000000000000000000000000000000' +
  '00000000000000000000000000000000' +
  '00000000000000000000000000000000' +
  '00000000000000000000000000000000' +
  '00000000000000000000000000000000' +
  '00000000000000000000000000000000' +
  'FFFFFFFFFFFFFFFFC90FDAA22168C234' +
  'C4C6628B80DC1CD129024E088A67CC74' +
  '020BBEA63B139B22514A08798E3404DD' +
  'EF9519B3CD3A431B302B0A6DF25F1437' +
  '4FE1356D6D51C245E485B576625E7EC6' +
  'F44C42E9A637ED6B0BFF5CB6F406B7ED' +
  'EE386BFB5A899FA5AE9F24117C4B1FE6' +
  '49286651ECE65381FFFFFFFFFFFFFFFF';

const SRP_INPUTS = [
  { label: 'ascii-short-v4',  password: 'pass',                         saltB64: 'AAECAwQFBgcICQoLDA0ODw==', modulusHex: TEST_MODULUS_HEX, version: 4 },
  { label: 'ascii-long-v4',   password: 'correct horse battery staple', saltB64: 'qrvM3e7/ABEiM0RVZneImQ==', modulusHex: TEST_MODULUS_HEX, version: 4 },
  { label: 'utf8-unicode-v4', password: 'pässwörd-Ω',                  saltB64: '3q2+78r+/s7wDbrqEjRWeA==', modulusHex: TEST_MODULUS_HEX, version: 4 },
];

// ---------------------------------------------------------------------------
// Capture loops
// ---------------------------------------------------------------------------

async function captureKeyPassword() {
  const out = [];
  for (const inp of KEY_PASSWORD_INPUTS) {
    const result = await computeKeyPassword(inp.password, inp.saltB64);
    out.push({
      label: inp.label,
      password: inp.password,
      saltB64: inp.saltB64,
      expected: result,
    });
  }
  return out;
}

async function captureSrp() {
  const out = [];
  for (const inp of SRP_INPUTS) {
    const modulusBytes = Buffer.from(inp.modulusHex, 'hex');
    const result = await hashPasswordV4(inp.password, inp.saltB64, modulusBytes);
    out.push({
      label: inp.label,
      password: inp.password,
      saltB64: inp.saltB64,
      modulusHex: inp.modulusHex,
      version: inp.version,
      expectedHex: result.toString('hex'),
    });
  }
  return out;
}

async function captureOpenPgp() {
  const openpgp = await import('openpgp');

  // Generate a test RSA-2048 keypair — unprotected for test vectors.
  const { privateKey: armoredPrivate, publicKey: armoredPublic } = await openpgp.generateKey({
    type: 'rsa',
    rsaBits: 2048,
    userIDs: [{ name: 'Test', email: 'test@example.com' }],
    passphrase: '',
    format: 'armored'
  });

  const privateKey = await openpgp.readPrivateKey({ armoredKey: armoredPrivate });
  const publicKey = await openpgp.readKey({ armoredKey: armoredPublic });

  const vectors = [];

  // Vector 1: detached sign + verify (binary mode)
  const plaintext1 = 'the quick brown fox jumps over the lazy dog';
  const sig1 = await openpgp.sign({
    message: await openpgp.createMessage({ binary: new TextEncoder().encode(plaintext1) }),
    signingKeys: privateKey,
    detached: true,
    format: 'armored'
  });
  vectors.push({
    label: 'sign-verify-binary',
    operation: 'signDetached',
    plaintext: plaintext1,
    armoredPublicKey: armoredPublic,
    armoredPrivateKey: armoredPrivate,
    armoredSignature: sig1,
  });

  // Vector 2: detached sign + verify (vCard-like content with CRLF)
  const plaintext2 = 'BEGIN:VCARD\r\nFN:Alice\r\nEMAIL:alice@example.com\r\nEND:VCARD\r\n';
  const sig2 = await openpgp.sign({
    message: await openpgp.createMessage({ binary: new TextEncoder().encode(plaintext2) }),
    signingKeys: privateKey,
    detached: true,
    format: 'armored'
  });
  vectors.push({
    label: 'sign-verify-vcard',
    operation: 'signDetached',
    plaintext: plaintext2,
    armoredPublicKey: armoredPublic,
    armoredPrivateKey: armoredPrivate,
    armoredSignature: sig2,
  });

  // Vector 3: encrypt + sign (detached) then decrypt + verify
  const plaintext3 = 'BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Bob Smith\r\nEMAIL:bob@example.com\r\nTEL:+1-555-0199\r\nEND:VCARD\r\n';
  const encrypted = await openpgp.encrypt({
    message: await openpgp.createMessage({ binary: new TextEncoder().encode(plaintext3) }),
    encryptionKeys: publicKey,
    format: 'armored'
  });
  // Separate detached binary signature — matches Proton's model where the
  // detached signature over plaintext is independent of the encrypted blob.
  const sig3 = await openpgp.sign({
    message: await openpgp.createMessage({ binary: new TextEncoder().encode(plaintext3) }),
    signingKeys: privateKey,
    detached: true,
    format: 'armored'
  });
  vectors.push({
    label: 'encrypt-sign-decrypt-verify',
    operation: 'encryptAndSignDetached',
    plaintext: plaintext3,
    armoredPublicKey: armoredPublic,
    armoredPrivateKey: armoredPrivate,
    armoredMessage: encrypted,
    armoredDetachedSignature: sig3,
  });

  return vectors;
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

try {
  const keyPasswordVectors = await captureKeyPassword();
  const srpVectors = await captureSrp();
  const openPgpVectors = await captureOpenPgp();

  const out = {
    generatedAt: new Date().toISOString(),
    protonCryptoPackageVersion: tryReadVersion(),
    schemaVersion: 2,
    notes: [
      'Generated by tools/vectors/capture.js — do not edit by hand.',
      'Add new inputs there + re-run; commit the resulting JSON alongside the script change.',
    ],
    computeKeyPassword: keyPasswordVectors,
    srpHashPassword: srpVectors,
    openPgp: openPgpVectors,
  };

  const targetDir = path.resolve(__dirname, '..', '..', 'core', 'crypto', 'src', 'test', 'resources');
  if (!fs.existsSync(targetDir)) fs.mkdirSync(targetDir, { recursive: true });
  const targetPath = path.join(targetDir, 'proton-crypto-vectors.json');
  fs.writeFileSync(targetPath, JSON.stringify(out, null, 2) + '\n', 'utf8');

  console.log(`Wrote ${out.computeKeyPassword.length} keyPassword, ${out.srpHashPassword.length} SRP, ${out.openPgp.length} OpenPGP vectors`);
  console.log(`     → ${targetPath}`);
} catch (e) {
  console.error('capture failed:', e && e.stack ? e.stack : e);
  process.exit(2);
}

function tryReadVersion() {
  try {
    const pkgPath = path.join(__dirname, 'node_modules', '@protontech', 'crypto', 'package.json');
    const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
    return pkg.version || 'unknown';
  } catch {
    return 'unknown';
  }
}
