import * as crypto from 'crypto';
import * as os from 'os';

const CIPHER_ALGO = 'aes-256-gcm';
const TOKEN_PREFIX = 'v1';

/**
 * 第一阶段不接系统钥匙串，使用本机用户信息派生密钥，避免 worker_token 明文落盘。
 * 后续桌面端可替换为 macOS Keychain / Windows Credential Manager。
 */
function deriveKey(): Buffer {
  const seed = `${os.platform()}|${os.arch()}|${os.hostname()}|${os.userInfo().username}`;
  return crypto.scryptSync(seed, 'AI-Test-Worker', 32);
}

export function encryptSecret(secret: string): string {
  if (!secret) return '';
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv(CIPHER_ALGO, deriveKey(), iv);
  const encrypted = Buffer.concat([cipher.update(secret, 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  return [TOKEN_PREFIX, iv.toString('base64'), tag.toString('base64'), encrypted.toString('base64')].join(':');
}

export function decryptSecret(cipherText: string): string {
  if (!cipherText) return '';
  const parts = cipherText.split(':');
  if (parts.length !== 4 || parts[0] !== TOKEN_PREFIX) {
    throw new Error('本地凭证格式不正确，请重新绑定设备。');
  }
  try {
    const iv = Buffer.from(parts[1], 'base64');
    const tag = Buffer.from(parts[2], 'base64');
    const encrypted = Buffer.from(parts[3], 'base64');
    const decipher = crypto.createDecipheriv(CIPHER_ALGO, deriveKey(), iv);
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(encrypted), decipher.final()]).toString('utf8');
  } catch {
    throw new Error('本地凭证无法解密，可能来自其他用户或设备，请重新绑定。');
  }
}
