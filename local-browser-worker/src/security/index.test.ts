import { describe, expect, it } from 'vitest';
import { decryptSecret, encryptSecret } from './index';

describe('security secret storage', () => {
  it('encrypts and decrypts token without keeping plaintext', () => {
    const token = 'worker-token-123';
    const encrypted = encryptSecret(token);
    expect(encrypted).not.toBe(token);
    expect(encrypted.startsWith('v1:')).toBe(true);
    expect(decryptSecret(encrypted)).toBe(token);
  });

  it('returns empty string for empty secret', () => {
    expect(encryptSecret('')).toBe('');
    expect(decryptSecret('')).toBe('');
  });

  it('throws Chinese error for invalid cipher text', () => {
    expect(() => decryptSecret('plain-token')).toThrow('本地凭证格式不正确');
  });
});
