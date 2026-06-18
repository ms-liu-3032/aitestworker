import { useState, useCallback } from 'react';

const tools = [
  { key: 'id-card', name: '大陆身份证', icon: '🪪', desc: '生成随机大陆身份证号码，支持省市区、出生日期、性别、批量生成' },
  { key: 'timestamp', name: '时间戳', icon: '⏱️', desc: 'Unix 时间戳与日期时间互转' },
  { key: 'json', name: 'JSON 格式化', icon: '{}', desc: 'JSON 数据校验、格式化、压缩' },
  { key: 'yaml', name: 'YAML 格式化', icon: '📄', desc: 'YAML 数据校验、格式化' },
];

function generateIdCard(options: { province?: string; gender?: string } = {}) {
  const now = new Date();
  const areaCode = options.province || '110101';
  const year = now.getFullYear() - Math.floor(Math.random() * 40 + 18);
  const month = String(Math.floor(Math.random() * 12) + 1).padStart(2, '0');
  const day = String(Math.floor(Math.random() * 28) + 1).padStart(2, '0');
  const birthday = `${year}${month}${day}`;
  const seq = Math.floor(Math.random() * 999) + 1;
  const base = `${areaCode}${birthday}${String(seq).padStart(3, '0')}`;
  const weights = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2];
  const checkChars = '10X98765432';
  let sum = 0;
  for (let i = 0; i < 17; i++) sum += parseInt(base[i]) * weights[i];
  return base + checkChars[sum % 11];
}

function validateIdCard(id: string): { valid: boolean; info: string } {
  if (id.length !== 18) return { valid: false, info: '身份证号码必须为18位' };
  if (!/^\d{17}[\dXx]$/.test(id)) return { valid: false, info: '身份证号码格式不正确' };
  const weights = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2];
  const checkChars = '10X98765432';
  let sum = 0;
  for (let i = 0; i < 17; i++) sum += parseInt(id[i]) * weights[i];
  if (checkChars[sum % 11] !== id[17].toUpperCase()) return { valid: false, info: '校验位不正确' };
  const birthday = id.substring(6, 14);
  const gender = parseInt(id[16]) % 2 === 0 ? '女' : '男';
  return { valid: true, info: `出生日期: ${birthday.substring(0,4)}-${birthday.substring(4,6)}-${birthday.substring(6,8)}，性别: ${gender}` };
}

function copyText(text: string) {
  navigator.clipboard.writeText(text);
}

// ==================== 身份证工具 ====================
function IdCardTool() {
  const [province, setProvince] = useState('110101');
  const [gender, setGender] = useState('random');
  const [count, setCount] = useState(1);
  const [results, setResults] = useState<string[]>([]);
  const [validateInput, setValidateInput] = useState('');
  const [validateResult, setValidateResult] = useState('');
  const [copiedIdx, setCopiedIdx] = useState<number | null>(null);

  const handleCopyItem = (text: string, idx?: number) => {
    navigator.clipboard.writeText(text);
    if (idx !== undefined) { setCopiedIdx(idx); setTimeout(() => setCopiedIdx(null), 1500); }
  };

  const provinces: Record<string, string> = {
    '110101': '北京市东城区', '110102': '北京市西城区', '310101': '上海市黄浦区',
    '440301': '深圳市福田区', '330102': '杭州市上城区', '510104': '成都市锦江区',
    '440103': '广州市荔湾区', '500101': '重庆市万州区', '320102': '南京市玄武区',
    '420103': '武汉市江汉区', '610102': '西安市新城区', '500103': '重庆市渝中区',
  };

  const handleGenerate = () => {
    const generated = [];
    for (let i = 0; i < count; i++) generated.push(generateIdCard({ province, gender }));
    setResults(generated);
  };

  const handleValidate = () => {
    if (!validateInput) { setValidateResult('请输入身份证号码'); return; }
    const r = validateIdCard(validateInput);
    setValidateResult(r.valid ? `✅ 有效 - ${r.info}` : `❌ 无效 - ${r.info}`);
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <div>
          <label className="block text-xs text-gray-500 mb-1">出生地</label>
          <select value={province} onChange={e => setProvince(e.target.value)} className="px-3 py-2 border border-gray-200 rounded-lg text-sm">
            {Object.entries(provinces).map(([code, name]) => <option key={code} value={code}>{name}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-xs text-gray-500 mb-1">性别</label>
          <select value={gender} onChange={e => setGender(e.target.value)} className="px-3 py-2 border border-gray-200 rounded-lg text-sm">
            <option value="random">随机</option>
            <option value="male">男</option>
            <option value="female">女</option>
          </select>
        </div>
        <div>
          <label className="block text-xs text-gray-500 mb-1">数量</label>
          <input type="number" min={1} max={100} value={count} onChange={e => setCount(Math.min(100, Math.max(1, parseInt(e.target.value) || 1)))} className="w-20 px-3 py-2 border border-gray-200 rounded-lg text-sm" />
        </div>
        <button onClick={handleGenerate} className="min-h-10 shrink-0 px-4 py-2 bg-slate-900 text-white text-sm rounded-lg hover:bg-slate-800">生成</button>
      </div>
      {results.length > 0 && (
        <div className="bg-gray-50 border border-gray-200 rounded-lg p-3">
          <div className="mb-2 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <span className="text-xs text-gray-400">生成结果（{results.length}条）</span>
            <button onClick={() => copyText(results.join('\n'))} className="text-xs text-blue-600 hover:text-blue-700">复制全部</button>
          </div>
          {results.map((r, i) => (
            <div key={i} className="group flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between">
              <span className="break-all font-mono text-sm text-gray-900">{r}</span>
              <button onClick={() => handleCopyItem(r, i)} className="text-xs text-gray-400 hover:text-blue-600 opacity-0 group-hover:opacity-100 transition-opacity">
                {copiedIdx === i ? '✓' : '复制'}
              </button>
            </div>
          ))}
        </div>
      )}
      <div className="border-t border-gray-200 pt-4">
        <h3 className="text-sm font-medium text-gray-700 mb-2">身份证校验</h3>
        <div className="flex flex-col gap-2 sm:flex-row">
          <input type="text" value={validateInput} onChange={e => setValidateInput(e.target.value)} placeholder="输入18位身份证号码" className="flex-1 px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
          <button onClick={handleValidate} className="min-h-10 shrink-0 px-4 py-2 bg-gray-100 text-gray-700 text-sm rounded-lg hover:bg-gray-200">校验</button>
        </div>
        {validateResult && <div className="mt-2 text-sm text-gray-700">{validateResult}</div>}
      </div>
    </div>
  );
}

// ==================== 时间戳工具 ====================
function TimestampTool() {
  const [mode, setMode] = useState<'current' | 'ts2date' | 'date2ts'>('current');
  const [tsInput, setTsInput] = useState('');
  const [dateInput, setDateInput] = useState('');
  const [result, setResult] = useState('');

  const now = Math.floor(Date.now() / 1000);

  const handleModeChange = (newMode: 'current' | 'ts2date' | 'date2ts') => {
    setMode(newMode);
    setResult('');
    setTsInput('');
    setDateInput('');
  };

  const handleConvert = useCallback(() => {
    if (mode === 'ts2date') {
      const ts = parseInt(tsInput);
      if (isNaN(ts)) { setResult('请输入有效的时间戳'); return; }
      const date = ts > 1e12 ? new Date(ts) : new Date(ts * 1000);
      setResult(`北京时间: ${date.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })}\nUTC: ${date.toUTCString()}\nISO: ${date.toISOString()}\n秒级时间戳: ${Math.floor(date.getTime() / 1000)}\n毫秒级时间戳: ${date.getTime()}`);
    } else if (mode === 'date2ts') {
      const date = new Date(dateInput);
      if (isNaN(date.getTime())) { setResult('请输入有效的时间'); return; }
      setResult(`秒级时间戳: ${Math.floor(date.getTime() / 1000)}\n毫秒级时间戳: ${date.getTime()}\nISO: ${date.toISOString()}`);
    }
  }, [mode, tsInput, dateInput]);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2">
        <button onClick={() => handleModeChange('current')} className={`px-4 py-2 text-sm rounded-lg ${mode === 'current' ? 'bg-slate-900 text-white' : 'bg-gray-100 text-gray-700'}`}>当前时间戳</button>
        <button onClick={() => handleModeChange('ts2date')} className={`px-4 py-2 text-sm rounded-lg ${mode === 'ts2date' ? 'bg-slate-900 text-white' : 'bg-gray-100 text-gray-700'}`}>时间戳 → 日期</button>
        <button onClick={() => handleModeChange('date2ts')} className={`px-4 py-2 text-sm rounded-lg ${mode === 'date2ts' ? 'bg-slate-900 text-white' : 'bg-gray-100 text-gray-700'}`}>日期 → 时间戳</button>
      </div>
      {mode === 'current' && (
        <div className="space-y-3">
          <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
            <div className="text-xs text-gray-400 mb-1">当前时间戳（秒级）</div>
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
              <div className="break-all text-2xl font-mono font-bold text-gray-900">{now}</div>
              <button onClick={() => copyText(String(now))} className="text-xs text-blue-600 hover:text-blue-700">复制</button>
            </div>
            <div className="text-sm text-gray-500 mt-2">{new Date(now * 1000).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })}</div>
          </div>
          <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
            <div className="text-xs text-gray-400 mb-1">当前时间戳（毫秒级）</div>
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
              <div className="break-all text-lg font-mono font-bold text-gray-900">{Date.now()}</div>
              <button onClick={() => copyText(String(Date.now()))} className="text-xs text-blue-600 hover:text-blue-700">复制</button>
            </div>
          </div>
        </div>
      )}
      {mode === 'ts2date' && (
        <div className="space-y-3">
          <input type="text" value={tsInput} onChange={e => setTsInput(e.target.value)} placeholder="输入时间戳（秒级或毫秒级）" className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
          <button onClick={handleConvert} className="px-4 py-2 bg-slate-900 text-white text-sm rounded-lg hover:bg-slate-800">转换</button>
        </div>
      )}
      {mode === 'date2ts' && (
        <div className="space-y-3">
          <input type="text" value={dateInput} onChange={e => setDateInput(e.target.value)} placeholder="输入日期时间，如 2026-06-14 17:30:00" className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm focus:border-gray-400 outline-none" />
          <button onClick={handleConvert} className="px-4 py-2 bg-slate-900 text-white text-sm rounded-lg hover:bg-slate-800">转换</button>
        </div>
      )}
      {result && (
        <div className="bg-gray-50 border border-gray-200 rounded-lg p-3">
          <div className="mb-2 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <span className="text-xs text-gray-400">转换结果</span>
            <button onClick={() => copyText(result)} className="text-xs text-blue-600 hover:text-blue-700">复制全部</button>
          </div>
          <pre className="text-sm font-mono text-gray-900 whitespace-pre-wrap">{result}</pre>
        </div>
      )}
    </div>
  );
}

// ==================== JSON 格式化 ====================
function JsonFormatter() {
  const [input, setInput] = useState('');
  const [output, setOutput] = useState('');
  const [error, setError] = useState('');
  const [indent, setIndent] = useState(2);

  const handleFormat = useCallback(() => {
    try { setOutput(JSON.stringify(JSON.parse(input), null, indent)); setError(''); }
    catch (e: any) { setError(`JSON 格式错误: ${e.message}`); setOutput(''); }
  }, [input, indent]);

  const handleMinify = useCallback(() => {
    try { setOutput(JSON.stringify(JSON.parse(input))); setError(''); }
    catch (e: any) { setError(`JSON 格式错误: ${e.message}`); setOutput(''); }
  }, [input]);

  const handleValidate = useCallback(() => {
    try { JSON.parse(input); setError(''); setOutput('✅ JSON 格式正确'); }
    catch (e: any) { setError(`❌ JSON 格式错误: ${e.message}`); setOutput(''); }
  }, [input]);

  return (
    <div className="space-y-4">
      <div>
        <label className="block text-xs font-medium text-gray-500 mb-1">输入 JSON</label>
        <textarea value={input} onChange={e => setInput(e.target.value)} rows={12} placeholder='{"key": "value"}' className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm font-mono focus:border-gray-400 outline-none resize-none" />
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <button onClick={handleFormat} className="px-4 py-2 bg-slate-900 text-white text-sm rounded-lg hover:bg-slate-800">格式化</button>
        <button onClick={handleMinify} className="px-4 py-2 bg-gray-100 text-gray-700 text-sm rounded-lg hover:bg-gray-200">压缩</button>
        <button onClick={handleValidate} className="px-4 py-2 bg-gray-100 text-gray-700 text-sm rounded-lg hover:bg-gray-200">校验</button>
        <select value={indent} onChange={e => setIndent(Number(e.target.value))} className="px-2 py-1 border border-gray-200 rounded text-xs">
          <option value={2}>缩进 2</option>
          <option value={4}>缩进 4</option>
          <option value={1}>缩进 1</option>
        </select>
      </div>
      {error && <div className="text-sm text-red-600 bg-red-50 p-3 rounded-lg">{error}</div>}
      {output && (
        <div className="bg-gray-50 border border-gray-200 rounded-lg p-3">
          <div className="mb-2 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <span className="text-xs text-gray-400">输出结果</span>
            <button onClick={() => copyText(output)} className="text-xs text-blue-600 hover:text-blue-700">复制</button>
          </div>
          <pre className="text-sm font-mono text-gray-900 whitespace-pre-wrap overflow-auto max-h-96">{output}</pre>
        </div>
      )}
    </div>
  );
}

// ==================== YAML 格式化 ====================
function YamlFormatter() {
  const [input, setInput] = useState('');
  const [output, setOutput] = useState('');
  const [error, setError] = useState('');

  const parseYaml = (text: string): any => {
    const lines = text.split('\n');
    const root: any = {};
    const stack: { obj: any; indent: number }[] = [{ obj: root, indent: -1 }];

    for (const line of lines) {
      if (line.trim() === '' || line.trim().startsWith('#')) continue;

      const indent = line.search(/\S/);
      const trimmed = line.trim();

      // 回退到正确的父级
      while (stack.length > 1 && indent <= stack[stack.length - 1].indent) {
        stack.pop();
      }
      const parent = stack[stack.length - 1].obj;

      if (trimmed.startsWith('- ')) {
        // 数组项
        const itemValue = trimmed.substring(2).trim();
        // 找到当前父级对象中最后一个数组
        const lastKey = Object.keys(parent).pop();
        if (lastKey && Array.isArray(parent[lastKey])) {
          parent[lastKey].push(parseYamlValue(itemValue));
        }
      } else if (trimmed.includes(':')) {
        const colonIdx = trimmed.indexOf(':');
        const key = trimmed.substring(0, colonIdx).trim();
        const value = trimmed.substring(colonIdx + 1).trim();

        if (value === '' || value === '|' || value === '>') {
          // 子对象或数组
          const child: any = Array.isArray(parent) ? {} : {};
          if (Array.isArray(parent)) {
            // 如果父是数组，找到最后一个对象
            const lastItem = parent[parent.length - 1];
            if (lastItem && typeof lastItem === 'object') {
              lastItem[key] = child;
            }
          } else {
            parent[key] = child;
          }
          stack.push({ obj: child, indent: indent });
        } else {
          parent[key] = parseYamlValue(value);
        }
      }
    }
    return root;
  };

  const parseYamlValue = (value: string): any => {
    if (value === 'true') return true;
    if (value === 'false') return false;
    if (value === 'null' || value === '~') return null;
    if (/^\d+$/.test(value)) return parseInt(value);
    if (/^\d+\.\d+$/.test(value)) return parseFloat(value);
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      return value.slice(1, -1);
    }
    return value;
  };

  const yamlToString = (obj: any, indent = 0): string => {
    const prefix = '  '.repeat(indent);
    let result = '';
    for (const [key, value] of Object.entries(obj)) {
      if (value === null || value === undefined) result += `${prefix}${key}: null\n`;
      else if (typeof value === 'object' && !Array.isArray(value)) result += `${prefix}${key}:\n${yamlToString(value as any, indent + 1)}`;
      else if (Array.isArray(value)) { result += `${prefix}${key}:\n`; for (const item of value) result += `${prefix}  - ${item}\n`; }
      else result += `${prefix}${key}: ${value}\n`;
    }
    return result;
  };

  const handleFormat = useCallback(() => {
    try { setOutput(yamlToString(parseYaml(input))); setError(''); }
    catch (e: any) { setError(`YAML 格式错误: ${e.message}`); setOutput(''); }
  }, [input]);

  const handleValidate = useCallback(() => {
    try { parseYaml(input); setError(''); setOutput('✅ YAML 格式正确'); }
    catch (e: any) { setError(`❌ YAML 格式错误: ${e.message}`); setOutput(''); }
  }, [input]);

  return (
    <div className="space-y-4">
      <div>
        <label className="block text-xs font-medium text-gray-500 mb-1">输入 YAML</label>
        <textarea value={input} onChange={e => setInput(e.target.value)} rows={12} placeholder="key: value" className="w-full px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm font-mono focus:border-gray-400 outline-none resize-none" />
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <button onClick={handleFormat} className="px-4 py-2 bg-slate-900 text-white text-sm rounded-lg hover:bg-slate-800">格式化</button>
        <button onClick={handleValidate} className="px-4 py-2 bg-gray-100 text-gray-700 text-sm rounded-lg hover:bg-gray-200">校验</button>
      </div>
      {error && <div className="text-sm text-red-600 bg-red-50 p-3 rounded-lg">{error}</div>}
      {output && (
        <div className="bg-gray-50 border border-gray-200 rounded-lg p-3">
          <div className="mb-2 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <span className="text-xs text-gray-400">输出结果</span>
            <button onClick={() => copyText(output)} className="text-xs text-blue-600 hover:text-blue-700">复制</button>
          </div>
          <pre className="text-sm font-mono text-gray-900 whitespace-pre-wrap overflow-auto max-h-96">{output}</pre>
        </div>
      )}
    </div>
  );
}

// ==================== 主组件 ====================
export default function TestTools() {
  const [activeTool, setActiveTool] = useState(tools[0].key);
  const renderTool = () => {
    switch (activeTool) {
      case 'id-card': return <IdCardTool />;
      case 'timestamp': return <TimestampTool />;
      case 'json': return <JsonFormatter />;
      case 'yaml': return <YamlFormatter />;
      default: return null;
    }
  };
  const currentTool = tools.find(t => t.key === activeTool);
  return (
    <div className="flex min-h-[calc(100vh-56px)] flex-col overflow-hidden animate-fade-in lg:h-[calc(100vh-56px)] lg:flex-row">
      <div className="flex max-h-72 w-full shrink-0 flex-col overflow-y-auto border-b border-gray-200 bg-white lg:max-h-none lg:w-56 lg:border-b-0 lg:border-r">
        <div className="px-4 py-3 border-b border-gray-100">
          <div className="text-[10px] uppercase tracking-wider text-gray-400 font-semibold">测试工具</div>
        </div>
        <div className="py-1 px-2">
          {tools.map(t => (
            <button key={t.key} onClick={() => setActiveTool(t.key)} className={`w-full text-left px-3 py-2.5 rounded-lg text-sm transition-colors mb-0.5 ${activeTool === t.key ? 'bg-gray-100 text-gray-900 font-medium' : 'text-gray-600 hover:bg-gray-50'}`}>
              <span className="mr-2">{t.icon}</span>{t.name}
            </button>
          ))}
        </div>
      </div>
      <div className="min-w-0 flex-1 overflow-y-auto bg-gray-50 p-4 sm:p-6">
        <div className="max-w-3xl min-w-0">
          <h1 className="text-xl font-bold text-gray-900 tracking-tight mb-1">{currentTool?.name}</h1>
          <p className="text-sm text-gray-500 mb-6">{currentTool?.desc}</p>
          {renderTool()}
        </div>
      </div>
    </div>
  );
}
