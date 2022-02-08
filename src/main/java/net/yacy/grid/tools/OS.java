/**
 *  OS
 *  Copyright 14.01.2017 by Michael Peter Christen, @orbiterlab
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.grid.tools;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class OS {

  // constants for system identification
  public enum System {
      MacOSX,  // all Mac OS X
      Unix,    // all Unix/Linux type systems
      Windows, // all Windows 95/98/NT/2K/XP
      Unknown; // any other system
  }

  // system-identification statics
  public static final System  systemOS;
  public static final boolean isMacArchitecture;
  public static final boolean isUnixFS;
  public static final boolean canExecUnix;
  public static final boolean isWindows;
  public static final boolean isWin32;

  // calculated system constants
  public static int maxPathLength = 65535;

  // Macintosh-specific statics
  public  static final Map<String, String> macFSTypeCache = new HashMap<String, String>();
  public  static final Map<String, String> macFSCreatorCache = new HashMap<String, String>();

  private final static Set<PosixFilePermission> securePerm = new HashSet<PosixFilePermission>();

  // system beans
  private static OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

  // static initialization
  static {
      // check operation system type
      final Properties sysprop = java.lang.System.getProperties();
      final String sysname = sysprop.getProperty("os.name","").toLowerCase();
      if (sysname.startsWith("mac os x")) systemOS = System.MacOSX;
      else if (sysname.startsWith("windows")) systemOS = System.Windows;
      else if ((sysname.startsWith("linux")) || (sysname.startsWith("unix"))) systemOS = System.Unix;
      else systemOS = System.Unknown;

      isMacArchitecture = systemOS == System.MacOSX;
      isUnixFS = systemOS == System.MacOSX || systemOS == System.Unix;
      canExecUnix = isUnixFS || systemOS != System.Windows;
      isWindows = systemOS == System.Windows;
      isWin32 = isWindows && java.lang.System.getProperty("os.arch", "").contains("x86");

      // set up maximum path length according to system
      if (isWindows) maxPathLength = 255; else maxPathLength = 65535;

      securePerm.add(PosixFilePermission.OWNER_READ);
      securePerm.add(PosixFilePermission.OWNER_WRITE);
      securePerm.add(PosixFilePermission.OWNER_EXECUTE);
  }

  public final static void protectPath(Path path) {
      try {
          Files.setPosixFilePermissions(path, securePerm);
      } catch (UnsupportedOperationException | IOException e) {}
  }

  private static long copy(final InputStream source, final OutputStream dest, final long count)
          throws IOException {
      assert count < 0 || count > 0 : "precondition violated: count == " + count + " (nothing to copy)";
      if ( count == 0 ) {
          // no bytes to copy
          return 0;
      }

      final byte[] buffer = new byte[2048];
      int chunkSize = (int) ((count > 0) ? Math.min(count, 2048) : 2048);

      int c;
      long total = 0;
      while ( (c = source.read(buffer, 0, chunkSize)) > 0 ) {
          dest.write(buffer, 0, c);
          dest.flush();
          total += c;

          if ( count > 0 && count == total) {
              break;
          }

      }
      dest.flush();

      return total;
  }

  private static void copy(final InputStream source, final File dest, final long count) throws IOException {
      final String path = dest.getParent();
      if ( path != null && path.length() > 0 ) {
          new File(path).mkdirs();
      }
      FileOutputStream fos = null;
      try {
          fos = new FileOutputStream(dest);
          copy(source, fos, count);
      } finally {
          if ( fos != null ) {
              try {
                  fos.close();
              } catch (final Exception e ) {
                  Logger.warn(e);
              }
          }
      }
  }

  private static void copy(final InputStream source, final File dest) throws IOException {
      copy(source, dest, -1);
  }


  private static void copy(final byte[] source, final File dest) throws IOException {
      copy(new ByteArrayInputStream(source), dest);
  }

  private static void deployScript(final File scriptFile, final String theScript) throws IOException {
      copy(theScript.getBytes(StandardCharsets.UTF_8), scriptFile);
      if(!isWindows){ // set executable
          try {
              Runtime.getRuntime().exec("chmod 755 " + scriptFile.getAbsolutePath().replaceAll(" ", "\\ ")).waitFor();
          } catch (final InterruptedException e) {
              Logger.warn("DEPLOY of script file failed. file = " + scriptFile.getAbsolutePath(), e);
              throw new IOException(e.getMessage());
          }
      }
  }

  /**
   * use a hack to get the current process PID
   * @return the PID of the current java process or -1 if the PID cannot be obtained
   */
  public static int getPID() {
      final String pids = ManagementFactory.getRuntimeMXBean().getName();
      final int p = pids.indexOf('@');
      return p >= 0 ? parseIntDecSubstring(pids, 0, p) : -1;
  }

  private static final int parseIntDecSubstring(String s, int startPos, final int endPos) throws NumberFormatException {
      if (s == null || endPos > s.length() || endPos <= startPos) throw new NumberFormatException(s);

      int result = 0;
      boolean negative = false;
      int i = startPos;
      int limit = -Integer.MAX_VALUE;
      final int multmin;
      int digit;
      char c;

      char firstChar = s.charAt(i);
      if (firstChar < '0') {
          if (firstChar == '-') {
              negative = true;
              limit = Integer.MIN_VALUE;
          } else if (firstChar != '+') throw new NumberFormatException(s);
          i++;
          if (endPos == i) throw new NumberFormatException(s);
      }
      multmin = limit / 10;
      while (i < endPos) {
          c = s.charAt(i++);
          if (c == ' ') break;
          digit = c - '0';
          if (digit < 0  || digit > 9 || result < multmin) throw new NumberFormatException(s);
          result *= 10;
          //result = (result << 3) + (result << 1);
          if (result < limit + digit) throw new NumberFormatException(s);
          result -= digit;
      }
      return negative ? result : -result;
  }

  private static final String LF_STRING = new String(new byte[]{10}, 0, 1, StandardCharsets.UTF_8);

  public static void execAsynchronous(final File scriptFile) throws IOException {
      // runs a script as separate thread
      String starterFileExtension = null;
      String script = null;
      if(isWindows){
          starterFileExtension = ".starter.bat";
          // use /K to debug, /C for release
          script = "start /MIN CMD /C \"" + scriptFile.getAbsolutePath() + "\"";
      } else { // unix/linux
          starterFileExtension = ".starter.sh";
          script = "#!/bin/sh" + LF_STRING + scriptFile.getAbsolutePath().replaceAll(" ", "\\ ") + " &" + LF_STRING;
      }
      final File starterFile = new File(scriptFile.getAbsolutePath().replaceAll(" ", "\\ ") + starterFileExtension);
      deployScript(starterFile, script);
      try {
          Runtime.getRuntime().exec(starterFile.getAbsolutePath().replaceAll(" ", "\\ ")).waitFor();
      } catch (final InterruptedException e) {
          throw new IOException(e.getMessage());
      }
      starterFile.delete();
  }

  public static List<String> execSynchronous(final String command) throws IOException {
      // runs a unix/linux command and returns output as Vector of Strings
      // this method blocks until the command is executed
      final Process p = Runtime.getRuntime().exec(command);
      return execSynchronousProcess(p);
  }

  private static List<String> execSynchronousProcess(Process p) throws IOException {
      String line;
      BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
      final List<String> output = new ArrayList<String>();
      while ((line = in.readLine()) != null) output.add(line);
      in.close();
      in = new BufferedReader(new InputStreamReader(p.getErrorStream()));
      while ((line = in.readLine()) != null) output.add(line);
      in.close();
      return output;
  }

  public static double getSystemLoadAverage() {
      return osBean.getSystemLoadAverage();
  }

  public static double getSystemCpuLoad() {
      return getOSBean("getSystemCpuLoad");
  }

  public static double getProcessCpuLoad() {
      return getOSBean("getProcessCpuLoad");
  }

  private static double getOSBean(String name) {
      try {
          Method m = osBean.getClass().getMethod(name);
          m.setAccessible(true);
          Object o = m.invoke(osBean);
          if (o instanceof Double) return ((Double) o).doubleValue();
      } catch (Throwable e) {}
      return 0.0d;
  }

  /**
   * find out the number of thread deadlocks. WARNING: this is a time-consuming task
   * @return the number of deadlocked threads
   */
  public static long deadlocks() {
      long[] deadlockIDs = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
      if (deadlockIDs == null) return 0;
      return deadlockIDs.length;
  }

  /**
   * Check if port is in use. This is checked by opening a connection at the given address
   * @param addr the inet address of the server. To test a localhost address, pass null here
   * @param port the port to be tested
   * @return true if the port is in use, false if no service is running at that port.
   */
  public static boolean portIsOpen(InetAddress addr, int port) {
      try (Socket socket = new Socket(addr, port)) {
          return true;
      } catch (IOException e) {
          return false;
      }
  }

  public static boolean portIsOpen(String host, int port) {
      try (Socket socket = new Socket(host, port)) {
          return true;
      } catch (IOException e) {
          return false;
      }
  }

  public static boolean portIsOpen(String address) {
      int p = address.indexOf('@');
      if (p >= 0) address = address.substring(p + 1);
      p = address.indexOf(':');
      if (p < 0) return false;
      return portIsOpen(address.substring(0, p), Integer.parseInt(address.substring(p + 1)));
  }

}
