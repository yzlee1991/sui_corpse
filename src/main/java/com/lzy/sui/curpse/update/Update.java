package com.lzy.sui.curpse.update;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;

import com.lzy.sui.common.inf.UpdateInf;
import com.lzy.sui.common.rmi.RmiClient;
import com.lzy.sui.curpse.Curpse;

public class Update {

	final private static String SYSPATH = System.getProperty("user.home");

	final private static String UPDATEPATH = SYSPATH + File.separator + "suicls" + File.separator + "update";

	static {
		File file=new File(UPDATEPATH);
		if(!file.exists()){
			file.mkdirs();
		}
	}
	
	public static void checkAndUpdate() throws IOException {
		Curpse.newInstance().getCachedThreadPool().execute(() -> {
			try {
				// 0.如果存在已下载未更新的jar则停止跟新检测
				String updateFlagFile=UPDATEPATH + File.separator + "flag";
				File flagFile=new File(updateFlagFile);
				if(flagFile.exists()){
					return;
				}
				// 1.获取当前运行jar大小
				String dPath = SYSPATH + File.separator + "suicls" + File.separator + "sui.jar";
				File file = new File(dPath);
				long currentSize = file.length();
				// 2.获取远程jar大小
				Socket socket = Curpse.newInstance().getSocket();
				UpdateInf inf = (UpdateInf) RmiClient.lookup(socket, UpdateInf.class.getName());
				long lastestSize = inf.getCorpseLastestSize();
				// 3.比较下载
				if (currentSize != lastestSize) {
					long blockSize = 1024 * 1024;// 之后改成可配置块大小
					long blockCount = lastestSize / blockSize;
					blockCount = lastestSize % blockSize == 0 ? blockCount : blockCount + 1;
					String updateFile = UPDATEPATH + File.separator + "sui.jar";
					BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(updateFile, false));
					for (int i = 1; i <= blockCount; i++) {
						byte[] bytes = inf.getCorpseUpdatePart((int) blockSize, i);// 强转方法不合理
						bos.write(bytes);
					}
					bos.flush();
					bos.close();
					// 添加下载成功文件，bat脚本根据这个文件替换jar
					flagFile.createNewFile();
				}

			} catch (Exception e) {
				e.printStackTrace();
			}

		});
	}

}
