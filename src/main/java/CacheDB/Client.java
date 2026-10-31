package CacheDB;
import CacheDB.CacheDBResult.CADBPROCPARA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class Client {

	private static final Logger logger = LoggerFactory.getLogger(Client.class);
	private static final String TAG = "NCacheDBClient";

	/** 库文件所在目录 */
	private static final String LIB_DIR = "/home/ltmroot/libs";

	/** 按依赖顺序排列的库文件名（先加载被依赖的） */
	private static final String[] LIB_NAMES = new String[]{
			"libduallist.so",
			"libmemapi.so",
			"libncacheapi.so",
			"libiconv.so",
			"libCacheDBLib.so"
	};

	/** 标记本地库是否已成功加载，避免后续调用再抛 NoClassDefFoundError */
	private static volatile boolean initialized = false;

	/** 记录初始化失败原因，便于业务层判断 */
	private static volatile String initError = null;

	static {
		loadNativeLibraries();
	}

	/**
	 * 加载所有本地库，并打印详细日志
	 */
	private static void loadNativeLibraries() {
		logger.info("========== [{}] 开始加载本地库 ==========", TAG);

		// 1. 打印 JVM 环境信息
		printEnvInfo();

		// 2. 校验库目录
		File libDir = new File(LIB_DIR);
		if (!libDir.exists() || !libDir.isDirectory()) {
			initError = "库目录不存在或不是目录: " + LIB_DIR;
			logger.error(initError);
			return;
		}
		logger.info("库目录存在: {} (可读={}, 可执行={})",
				LIB_DIR, libDir.canRead(), libDir.canExecute());

		// 3. 打印目录下所有 .so 文件，便于确认文件确实存在
		listSoFiles(libDir);

		// 4. 逐个加载
		List<String> loaded = new ArrayList<>();
		try {
			for (String libName : LIB_NAMES) {
				File libFile = new File(libDir, libName);
				loadOne(libFile, libName);
				loaded.add(libName);
			}

			initialized = true;
			logger.info("========== [{}] 全部本地库加载成功 ==========", TAG);

		} catch (Throwable t) {
			// 注意：必须捕获 Throwable，UnsatisfiedLinkError 是 Error 不是 Exception
			initError = String.format("加载库失败，已成功加载: %s, 失败于: %s",
					loaded, t.getMessage());
			logger.error("========== [{}] 本地库加载失败 ==========", TAG);
			logger.error("已成功加载: {}", loaded);
			logger.error("失败原因: {}", t.getMessage(), t);
		}
	}

	/**
	 * 加载单个库文件
	 */
	private static void loadOne(File libFile, String libName) {
		logger.info("---- 准备加载: {} ----", libName);

		// 文件存在性校验
		if (!libFile.exists()) {
			throw new UnsatisfiedLinkError("文件不存在: " + libFile.getAbsolutePath());
		}
		if (!libFile.isFile()) {
			throw new UnsatisfiedLinkError("不是普通文件: " + libFile.getAbsolutePath());
		}
		if (!libFile.canRead()) {
			throw new UnsatisfiedLinkError("文件不可读: " + libFile.getAbsolutePath());
		}

		logger.info("  路径: {}", libFile.getAbsolutePath());
		logger.info("  大小: {} bytes", libFile.length());
		logger.info("  可读: {}, 可执行: {}", libFile.canRead(), libFile.canExecute());

		// 使用 ldd 检查依赖（仅 Linux 有效，失败不影响主流程）
		checkDependencies(libFile);

		long start = System.currentTimeMillis();
		try {
			// 使用绝对路径加载，避免 java.library.path 配置问题
			System.load(libFile.getAbsolutePath());
			logger.info("  ✅ 加载成功, 耗时 {} ms", System.currentTimeMillis() - start);
		} catch (Throwable t) {
			logger.error("  ❌ 加载失败: {}", t.getMessage());
			throw t;
		}
	}

	/**
	 * 使用 ldd 检查 .so 的依赖是否齐全
	 */
	private static void checkDependencies(File libFile) {
		// 只在 Linux 上执行
		String os = System.getProperty("os.name", "").toLowerCase();
		if (!os.contains("linux")) {
			return;
		}

		Process process = null;
		try {
			process = new ProcessBuilder("ldd", libFile.getAbsolutePath())
					.redirectErrorStream(true)
					.start();

			StringBuilder sb = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream()))) {
				String line;
				boolean hasMissing = false;
				while ((line = reader.readLine()) != null) {
					sb.append("    ").append(line).append("\n");
					if (line.contains("not found")) {
						hasMissing = true;
					}
				}
				process.waitFor();

				if (hasMissing) {
					logger.warn("  ⚠️ 依赖检查发现缺失库:\n{}", sb);
				} else {
					logger.info("  依赖检查通过 (ldd)");
				}
			}
		} catch (Exception e) {
			// ldd 不存在或执行失败，不影响主流程
			logger.warn("  ldd 检查跳过: {}", e.getMessage());
		} finally {
			if (process != null) {
				process.destroy();
			}
		}
	}

	/**
	 * 打印 JVM 环境信息
	 */
	private static void printEnvInfo() {
		logger.info("Java 版本: {}", System.getProperty("java.version"));
		logger.info("Java Home : {}", System.getProperty("java.home"));
		logger.info("OS 名称   : {} {}", System.getProperty("os.name"), System.getProperty("os.version"));
		logger.info("OS 架构   : {}", System.getProperty("os.arch"));
		logger.info("工作目录  : {}", System.getProperty("user.dir"));
		logger.info("java.library.path = {}", System.getProperty("java.library.path"));
		logger.info("LD_LIBRARY_PATH  = {}", System.getenv("LD_LIBRARY_PATH"));
	}

	/**
	 * 列出目录下所有 .so 文件
	 */
	private static void listSoFiles(File dir) {
		File[] files = dir.listFiles((d, name) -> name.endsWith(".so"));
		if (files == null || files.length == 0) {
			logger.warn("目录 {} 下未找到任何 .so 文件", dir.getAbsolutePath());
			return;
		}
		logger.info("目录 {} 下共 {} 个 .so 文件:", dir.getAbsolutePath(), files.length);
		for (File f : files) {
			logger.info("    {}  ({} bytes, 可读={}, 可执行={})",
					f.getName(), f.length(), f.canRead(), f.canExecute());
		}
	}

	/**
	 * 供业务层判断本地库是否可用，避免直接调用 native 方法导致 NoClassDefFoundError
	 */
	public static boolean isInitialized() {
		return initialized;
	}

	/**
	 * 获取初始化错误信息
	 */
	public static String getInitError() {
		return initError;
	}

	// ==================== Native 方法 ====================

	public native static long InitEnv(String sServer, int nPort, String sUsername,
									  String sPassword, String sDB,
									  String sPageCoding, String sDBCoding);

	public native static int Connect(long pClient);

	public native static int CloseConnect(long pClient);

	public native static int Destroy(long pClient);

	public native static String ExecProc(long pClient, String sSPName, int nParaCount,
										 CADBPROCPARA[] pPara);

	public native static String ExecDirectly(long pClient, String sQuery);

	public native static String GetDataSetItem(long pClient);

	public native static String GetDataFromDataSet(long pClient, int nDataSetNumber, int nRowNumber);

	public native static int FreeQueryHandle(long pClient);

	public native static int GetConnectStatus(long pClient);
}