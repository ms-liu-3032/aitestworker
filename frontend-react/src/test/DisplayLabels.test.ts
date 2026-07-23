import { describe, expect, it } from 'vitest';
import { displayLabel, statusLabel, wikiScopeLabel } from '../utils/displayLabels';

describe('display labels', () => {
  it('localizes common status, scope and role identifiers', () => {
    expect(statusLabel('ACTIVE')).toBe('已生效');
    expect(statusLabel('APPROVED')).toBe('审核通过');
    expect(wikiScopeLabel('REUSABLE')).toBe('跨项目复用级');
    expect(displayLabel('ADMIN')).toBe('管理员');
  });

  it('does not expose an unknown backend identifier directly', () => {
    expect(displayLabel('SOME_NEW_INTERNAL_STATE')).toBe('未配置中文标识');
  });
});
