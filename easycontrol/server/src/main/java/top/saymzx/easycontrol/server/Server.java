/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package top.saymzx.easycontrol.server;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.IInterface;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

import top.saymzx.easycontrol.server.entity.Device;
import top.saymzx.easycontrol.server.entity.Options;
import top.saymzx.easycontrol.server.helper.AudioEncode;
import top.saymzx.easycontrol.server.helper.Controller;
import top.saymzx.easycontrol.server.helper.VideoEncode;
import top.saymzx.easycontrol.server.wrappers.ClipboardManager;
import top.saymzx.easycontrol.server.wrappers.DisplayManager;
import top.saymzx.easycontrol.server.wrappers.InputManager;
import top.saymzx.easycontrol.server.wrappers.SurfaceControl;
import top.saymzx.easycontrol.server.wrappers.WindowManager;

// 此部分代码摘抄借鉴了著名投屏软件Scrcpy的开源代码(https://github.com/Genymobile/scrcpy/tree/master/server)
public final class Server {
  private static Socket socket;
  public static DataInputStream inputStream;
  public static OutputStream outputStream;

  private static final Object object = new Object();

  private static final int timeoutDelay = 1000 * 10;

  public static void main(String... args) {
    // 启动超时
    Thread startCheckThread = new Thread(() -> {
      try {
        Thread.sleep(timeoutDelay);
        release();
      } catch (InterruptedException ignored) {
      }
    });
    startCheckThread.start();
    try {
      // 解析参数
      Options.parse(args);
      // 初始化
      setManagers();
      Device.init();
      // 连接
      connectClient();
      // 初始化子服务
      VideoEncode.init();
      boolean canAudio = AudioEncode.init();
      // 启动
      ArrayList<Thread> threads = new ArrayList<>();
      threads.add(new Thread(Server::executeVideoOut));
      if (canAudio) {
        threads.add(new Thread(Server::executeAudioIn));
        threads.add(new Thread(Server::executeAudioOut));
      }
      threads.add(new Thread(Server::executeControlIn));
      threads.add(new Thread(Server::executeOtherService));
      for (Thread thread : threads) thread.setPriority(Thread.MAX_PRIORITY);
      for (Thread thread : threads) thread.start();
      // 程序运行
      startCheckThread.interrupt();
      synchronized (object) {
        object.wait();
      }
      // 终止子服务
      for (Thread thread : threads) thread.interrupt();
    } catch (Exception ignored) {
    } finally {
      // 释放资源
      release();
    }
  }

  private static Method GET_SERVICE_METHOD;

  @SuppressLint({"DiscouragedPrivateApi", "PrivateApi"})
  private static void setManagers() throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    GET_SERVICE_METHOD = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class);
    // 1
    WindowManager.init(getService("window", "android.view.IWindowManager"));
    // 2
    DisplayManager.init(Class.forName("android.hardware.display.DisplayManagerGlobal").getDeclaredMethod("getInstance").invoke(null));
    // 3
    Class<?> inputManagerClass;
    try {
      inputManagerClass = Class.forName("android.hardware.input.InputManagerGlobal");
    } catch (ClassNotFoundException e) {
      inputManagerClass = android.hardware.input.InputManager.class;
    }
    InputManager.init(inputManagerClass.getDeclaredMethod("getInstance").invoke(null));
    // 4
    ClipboardManager.init(getService("clipboard", "android.content.IClipboard"));
    // 5
    SurfaceControl.init();
  }

  private static IInterface getService(String service, String type) {
    try {
      IBinder binder = (IBinder) GET_SERVICE_METHOD.invoke(null, service);
      Method asInterfaceMethod = Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class);
      return (IInterface) asInterfaceMethod.invoke(null, binder);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void connectClient() throws IOException {
    try (ServerSocket serverSocket = new ServerSocket(Options.tcpPort)) {
      socket = serverSocket.accept();
      socket.setTcpNoDelay(true);
      inputStream = new DataInputStream(socket.getInputStream());
      outputStream = socket.getOutputStream();
    }
  }

  private static void executeVideoOut() {
    try {
      while (!Thread.interrupted()) {
        if (VideoEncode.isHasChangeConfig) {
          VideoEncode.isHasChangeConfig = false;
          VideoEncode.stopEncode();
          VideoEncode.startEncode();
        }
        VideoEncode.encodeOut();
      }
    } catch (Exception ignored) {
      errorClose();
    }
  }

  private static void executeAudioIn() {
    while (!Thread.interrupted()) AudioEncode.encodeIn();
  }

  private static void executeAudioOut() {
    while (!Thread.interrupted()) AudioEncode.encodeOut();
  }

  private static void executeControlIn() {
    try {
      while (!Thread.interrupted()) Controller.handleIn();
    } catch (Exception ignored) {
      errorClose();
    }
  }

  private static void executeOtherService() {
    try {
      while (!Thread.interrupted()) {
        if (Options.autoControlScreen) Controller.checkScreenOff(true);
        if (Options.turnOffScreen) Device.setScreenPowerMode(0);
        if (System.currentTimeMillis() - Controller.lastKeepAliveTime > timeoutDelay) throw new IOException("连接断开");
        Thread.sleep(1500);
      }
    } catch (Exception ignored) {
      errorClose();
    }
  }

  public synchronized static void write(byte[] buffer) {
    try {
      outputStream.write(buffer);
    } catch (IOException ignored) {
      errorClose();
    }
  }

  private static void errorClose() {
    synchronized (object) {
      object.notify();
    }
  }

  // 释放资源
  private static void release() {
    for (int i = 0; i < 6; i++) {
      try {
        switch (i) {
          case 0:
            inputStream.close();
            outputStream.close();
            socket.close();
            break;
          case 1:
            if (Options.reSize != -1) Device.execReadOutput("wm size reset");
            break;
          case 2:
            VideoEncode.release();
            break;
          case 3:
            AudioEncode.release();
            break;
          case 4:
            if (Options.autoControlScreen) Controller.checkScreenOff(false);
            if (Options.turnOffScreen) Device.setScreenPowerMode(1);
            break;
          case 5:
            Device.execReadOutput("ps -ef | grep easycontrol.server | grep -v grep | grep -E \"^[a-z]+ +[0-9]+\" -o | grep -E \"[0-9]+\" -o | xargs kill -9");
            break;
        }
      } catch (Exception ignored) {
      }
    }
  }

}
