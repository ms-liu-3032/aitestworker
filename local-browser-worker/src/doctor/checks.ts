export interface CheckItem {
  name: string;
  status: '通过' | '警告' | '失败';
  message: string;
  suggestion: string;
}

/**
 * 检查操作系统是否受支持。
 */
export function checkOS(platform: string, osVersion: string): CheckItem {
  if (platform === 'darwin') {
    const major = parseInt(osVersion.split('.')[0], 10) || 0;
    if (major >= 12) {
      return {
        name: '操作系统',
        status: '通过',
        message: `macOS ${osVersion}`,
        suggestion: '',
      };
    }
    return {
      name: '操作系统',
      status: '失败',
      message: `macOS ${osVersion} (版本过低)`,
      suggestion: '请升级到 macOS 12 (Monterey) 或更高版本',
    };
  }
  if (platform === 'win32') {
    if (osVersion === '10' || osVersion === '11') {
      const osName = osVersion === '11' ? 'Windows 11' : 'Windows 10';
      return {
        name: '操作系统',
        status: '通过',
        message: `${osName} (${osVersion})`,
        suggestion: '',
      };
    }
    return {
      name: '操作系统',
      status: '失败',
      message: `Windows ${osVersion} (版本不支持)`,
      suggestion: '当前仅支持 Windows 10 / Windows 11',
    };
  }
  if (platform === 'linux') {
    return {
      name: '操作系统',
      status: '失败',
      message: `Linux (${osVersion})`,
      suggestion: '第一阶段不支持 Linux 桌面端。请在 Windows 10/11 或 macOS 12+ 上使用。',
    };
  }
  return {
    name: '操作系统',
    status: '失败',
    message: `不支持的操作系统: ${platform}`,
    suggestion: '当前仅支持 Windows 10/11、macOS 12+',
  };
}

/**
 * 检查 Node.js 版本是否满足最低要求。
 */
export function checkNodeVersion(version: string): CheckItem {
  const major = parseInt(version.replace(/^v/, '').split('.')[0], 10);
  if (major >= 20) {
    return {
      name: 'Node.js 版本',
      status: '通过',
      message: `${version} (推荐 20 LTS)`,
      suggestion: '',
    };
  }
  if (major >= 18) {
    return {
      name: 'Node.js 版本',
      status: '通过',
      message: `${version} (最低要求 18)`,
      suggestion: '建议升级到 Node.js 20 LTS 以获得最佳体验',
    };
  }
  return {
    name: 'Node.js 版本',
    status: '失败',
    message: `${version} (不满足最低要求)`,
    suggestion: '请安装 Node.js 18+ 或 20 LTS',
  };
}

/**
 * 检查 CPU 架构。
 */
export function checkArchitecture(arch: string): CheckItem {
  if (arch === 'x64' || arch === 'arm64') {
    return {
      name: 'CPU 架构',
      status: '通过',
      message: `${arch}`,
      suggestion: '',
    };
  }
  return {
    name: 'CPU 架构',
    status: '失败',
    message: `不支持的架构: ${arch}`,
    suggestion: '当前仅支持 x64 和 arm64 架构',
  };
}

/**
 * 检查 Chrome 浏览器路径是否可用。
 */
export function checkChrome(chromePath: string | null, platform: string): CheckItem {
  if (platform === 'linux') {
    return {
      name: 'Chrome 浏览器',
      status: '失败',
      message: '第一阶段不支持 Linux 桌面端，因此不检测 Chrome',
      suggestion: '请在 Windows 10/11 或 macOS 12+ 上重新执行 ai-test-worker doctor',
    };
  }
  if (chromePath) {
    return {
      name: 'Chrome 浏览器',
      status: '通过',
      message: `检测到 ${chromePath}`,
      suggestion: '',
    };
  }
  return {
    name: 'Chrome 浏览器',
    status: '警告',
    message: '未检测到系统 Chrome',
    suggestion: '如未安装 Chrome，请访问 https://www.google.com/chrome/ 下载安装。或系统将使用 Playwright Chromium 作为兜底。',
  };
}

/**
 * 检查 Playwright Chromium 缓存是否存在。
 */
export function checkPlaywrightChromium(exists: boolean): CheckItem {
  if (exists) {
    return {
      name: 'Playwright Chromium',
      status: '通过',
      message: 'Playwright Chromium 已安装',
      suggestion: '',
    };
  }
  return {
    name: 'Playwright Chromium',
    status: '警告',
    message: 'Playwright Chromium 尚未安装',
    suggestion: '请执行 npx playwright install chromium 安装浏览器',
  };
}

/**
 * 检查数据目录路径。
 */
export function checkDataDir(dirPath: string): CheckItem {
  return {
    name: '本地数据目录',
    status: '通过',
    message: dirPath,
    suggestion: '',
  };
}

/**
 * 检查缓存目录路径。
 */
export function checkCacheDir(dirPath: string): CheckItem {
  return {
    name: '本地缓存目录',
    status: '通过',
    message: dirPath,
    suggestion: '',
  };
}

/**
 * 检查端口是否可用。
 */
export function checkPort(available: boolean, port: number, errorCode?: string): CheckItem {
  if (available) {
    return {
      name: `默认端口 ${port}`,
      status: '通过',
      message: `端口 ${port} 可用`,
      suggestion: '',
    };
  }
  if (errorCode === 'EPERM' || errorCode === 'EACCES') {
    return {
      name: `默认端口 ${port}`,
      status: '失败',
      message: `当前环境不允许监听 127.0.0.1:${port}`,
      suggestion: '请确认终端或安全软件允许本地端口监听，或稍后在本机普通终端重新执行 ai-test-worker doctor',
    };
  }
  return {
    name: `默认端口 ${port}`,
    status: '失败',
    message: `端口 ${port} 已被占用`,
    suggestion: `请修改配置文件中的 port 字段，或释放端口 ${port}`,
  };
}
