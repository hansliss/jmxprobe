package nu.liss;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.Thread.State;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryManagerMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanInfo;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import gnu.getopt.Getopt;

public class JMXProbe {
	protected String conffilename;
	protected String host;
	protected String service;
	protected String username;
	protected String password;
	protected boolean usessl;
	protected boolean csv=true;
	protected boolean headers=false;
	protected boolean listbeans=false;
	protected boolean allcolumns=false;
	protected boolean verbose=false;
	List<String> columns = new ArrayList<String>();

	private void doProbe() {
		Map<String, String[]> env = new HashMap<String, String[]>();
		Map<String,String> values = new HashMap<String, String>();
		if (host == null || service == null) {
			System.err.println("JMXProbe: Hostname and/or service missing. Check parameters and configuration file");
			return;
		}
		if (username != null && password != null) {
			String[] credentials = { username, password };
			env.put(JMXConnector.CREDENTIALS, credentials);
		}
		// Do all of it in a single try block - if anything fails, we might as well fail royally.
		try {
			JMXServiceURL url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + host + ":" + service + "/jmxrmi");
			JMXConnector connector = JMXConnectorFactory.connect(url, env);
			MBeanServerConnection connection = connector.getMBeanServerConnection();

			/****** List all beans ******/
			if (listbeans) {
				Set<ObjectInstance> beans = connection.queryMBeans(null,null);
				for( ObjectInstance instance : beans )
				{
					MBeanInfo info = connection.getMBeanInfo( instance.getObjectName() );
					System.out.println("MBean:" + info.getClassName() + ", " + info.getDescription());
					if (info.getClassName().equals("sun.management.MemoryPoolImpl")) {
						System.out.println(connection.getObjectInstance(instance.getObjectName()).getClassName());
					}
				}
			}
			/****** ******/

			/*
			 * We will collect all the info we find interesting, in a hash table with well-defined column names.
			 * Then we can emit this info in different forms later.
			 */

			/******  Threads ******/
			ThreadMXBean threadBean = null;
			ObjectName objName = new ObjectName(ManagementFactory.THREAD_MXBEAN_NAME);

			Set<ObjectName> mbeans = connection.queryNames(objName, null);
			for (ObjectName name : mbeans) {
				threadBean = ManagementFactory.newPlatformMXBeanProxy(connection, name.toString(), ThreadMXBean.class);
			}
			values.put("Thread count",String.format("%d",threadBean.getThreadCount()));
			List<ThreadInfo> tis = new ArrayList<ThreadInfo>();
			long[] tids = threadBean.getAllThreadIds(); 
			long blocked=0;
			long runnable=0;
			long waiting=0;
			long inNative=0;
			long unavailable=0;

			// getThreadInfo() will fail if we don't have control permission, which will most likely not have.
			// In this case, count the thread as "unavailable" - we could conceivably run into a case where
			// a particular class of thread are unavailable due to permission issues, but others aren't.
			for (long tid: tids) {
				try {
					tis.add(threadBean.getThreadInfo(tid));
				} catch (SecurityException sex) {
					unavailable++;
				}
			}
			for (ThreadInfo ti: tis) {
				if (ti.getThreadState().equals(State.BLOCKED)) {
					blocked++;
				} else if (ti.getThreadState().equals(State.RUNNABLE)) {
					runnable++;
				} else if (ti.getThreadState().equals(State.WAITING)) {
					waiting++;
				}
				if (ti.isInNative()) {
					inNative++;
				}
			}
			values.put("Thread count",String.format("%d",threadBean.getThreadCount()));
			values.put("Thread count - unavailable",String.format("%d",unavailable));
			values.put("Thread count - blocked",String.format("%d",blocked));
			values.put("Thread count - runnable",String.format("%d",runnable));
			values.put("Thread count - waiting",String.format("%d",waiting));
			values.put("Thread count - in native",String.format("%d",inNative));
			/****** Done with threads ******/

			/****** Global memory information ******/
			MemoryMXBean mbean = null;
			objName = new ObjectName(ManagementFactory.MEMORY_MXBEAN_NAME);
			mbeans = connection.queryNames(objName, null);
			for (ObjectName name : mbeans) {
				if (verbose) {
					System.out.println("MemoryMXBean name: "+name.toString());
				}
				mbean = ManagementFactory.newPlatformMXBeanProxy(connection, name.toString(), MemoryMXBean.class);
			}
			values.put("Memory - heap memory - used", String.format("%d",mbean.getHeapMemoryUsage().getUsed()));
			values.put("Memory - heap memory - committed", String.format("%d",mbean.getHeapMemoryUsage().getCommitted()));
			values.put("Memory - heap memory - max", String.format("%d",mbean.getHeapMemoryUsage().getMax()));
			values.put("Memory - non-heap memory - used", String.format("%d",mbean.getNonHeapMemoryUsage().getUsed()));
			values.put("Memory - non-heap memory - committed", String.format("%d",mbean.getNonHeapMemoryUsage().getCommitted()));
			values.put("Memory - non-heap memory - max", String.format("%d",mbean.getNonHeapMemoryUsage().getMax()));
			/****** Done with global memory information ******/

			/****** Classloading info ******/
			ClassLoadingMXBean clbean = null;
			objName = new ObjectName(ManagementFactory.CLASS_LOADING_MXBEAN_NAME);
			mbeans = connection.queryNames(objName, null);
			for (ObjectName name : mbeans) {
				if (verbose) {
					System.out.println("ClassLoadingMXBean name: "+name.toString());
				}
				clbean = ManagementFactory.newPlatformMXBeanProxy(connection, name.toString(), ClassLoadingMXBean.class);
				values.put("Classes - loaded", String.format("%d",clbean.getLoadedClassCount()));
				values.put("Classes - total loaded", String.format("%d",clbean.getTotalLoadedClassCount()));
				values.put("Classes - unloaded", String.format("%d",clbean.getUnloadedClassCount()));
			}
			/****** Done with classloading info ******/

			/****** Runtime info ******/
			// Nothing here used at the moment
			RuntimeMXBean proxy = 
				ManagementFactory.newPlatformMXBeanProxy(connection,
						ManagementFactory.RUNTIME_MXBEAN_NAME,
						RuntimeMXBean.class);
			String vendor = proxy.getVmVendor();
			/****** Done with runtime info ******/

			/****** Memory pool info ******/
			objName = new ObjectName(ManagementFactory.MEMORY_POOL_MXBEAN_DOMAIN_TYPE+",name=*");
			mbeans = connection.queryNames(objName, null);
			for (ObjectName name : mbeans) {
				MemoryPoolMXBean mpbean = ManagementFactory.newPlatformMXBeanProxy(connection, name.toString(), MemoryPoolMXBean.class); 
				if (verbose) {
					System.out.println("Memory pool name = " + mpbean.getName());
				}
				values.put("Memory Pool "+mpbean.getName()+" - used", String.format("%d", mpbean.getUsage().getUsed()));
				values.put("Memory Pool "+mpbean.getName()+" - committed", String.format("%d", mpbean.getUsage().getCommitted()));
				values.put("Memory Pool "+mpbean.getName()+" - max", String.format("%d", mpbean.getUsage().getMax()));
			}
			/****** Done with memory pool info ******/

			/****** Memory manager info ******/
			// Nothing of interest here, just testing...
			objName = new ObjectName(ManagementFactory.MEMORY_MANAGER_MXBEAN_DOMAIN_TYPE+",name=*");
			mbeans = connection.queryNames(objName, null);
			for (ObjectName name : mbeans) {
				MemoryManagerMXBean mmmbean = ManagementFactory.newPlatformMXBeanProxy(connection, name.toString(), MemoryManagerMXBean.class);
				if (verbose) {
					System.out.println("Memory manager name = " + mmmbean.getName());
				}
			}

			/****** Garbage collector info ******/
			objName = new ObjectName(ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE+",name=*");
			mbeans = connection.queryNames(objName, null);
			for (ObjectName name : mbeans) {
				GarbageCollectorMXBean gcmbean = ManagementFactory.newPlatformMXBeanProxy(connection, name.toString(), GarbageCollectorMXBean.class); 
				if (verbose) {
					System.out.println("Garbage collector name = " + gcmbean.getName());
				}
				double time=0;
				if (gcmbean.getCollectionCount() > 0) {
					time = (double)gcmbean.getCollectionTime() / gcmbean.getCollectionCount();
				}
				values.put("GC " + gcmbean.getName() + " avg time", String.format("%.4f", time));
				values.put("GC " + gcmbean.getName() + " count", String.format("%d",gcmbean.getCollectionCount()));
				values.put("GC " + gcmbean.getName() + " time", String.format("%d",gcmbean.getCollectionTime()));
			}
			/****** Done with garbage collector info ******/

			/****** Output the result ******/
			if (csv) {
				if (allcolumns) {
					columns.clear();
					Set<String> keys = values.keySet();
					for (String key: keys) {
						columns.add(key);
					}
					Collections.sort(columns);
				}
				if (headers) {
					System.out.print("Date/time");
					for (String col: columns) {
						System.out.print("," + col);
					}					
					System.out.println();
				}
				DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
				System.out.print(df.format(new Date()));
				for (String col: columns) {
					System.out.print(",");
					if (values.containsKey(col)) {
						System.out.print(values.get(col));
					}
				}
				System.out.println();
			} else {
				Set<String> keys = values.keySet();
				List<String> names = new ArrayList<String>();
				for (String key: keys) {
					names.add(key);
				}
				Collections.sort(names);
				for (String name: names) {
					System.out.println(name+": "+values.get(name));
				}
			}
			connector.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (MalformedObjectNameException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return;
		} catch (NullPointerException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return;
		} catch (InstanceNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IntrospectionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ReflectionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public JMXProbe(String[] args) {
		int c;

		Getopt go = new Getopt("JMXProbe", args, "h:s:U:P:c:C:lHBA");
		while ((c = go.getopt()) != -1) {
			switch (c) {
			case 'h':
				host = go.getOptarg();
				break;
			case 's':
				service = go.getOptarg();
				break;
			case 'U':
				username = go.getOptarg();
				break;
			case 'P':
				password = go.getOptarg();
				break;
			case 'c':
				conffilename = go.getOptarg();
				if (!readconf(conffilename)) {
					System.exit(-1);
				}
				break;
			case 'C':
				for (String col: go.getOptarg().split(","))
					columns.add(col);
				break;
			case 'l':
				csv=false;
				break;
			case 'H':
				headers=true;
				break;
			case 'B':
				listbeans=true;
				break;
			case 'A':
				allcolumns=true;
				break;
			}
		}

	}

	private static List<String> parseList(String ls) {
		List<String> r = new ArrayList<String>();
		for (String s : ls.split(",")) {
			if (s.contains("-")) {
				String[] ivl = s.split("-", 2);
				int start = Integer.parseInt(ivl[0]);
				int end = Integer.parseInt(ivl[1]);
				if (start > end) {
					int t = start;
					start = end;
					end = t;
				}
				for (Integer i = start; i <= end; i++) {
					r.add(i.toString());
				}
			} else {
				r.add(((Integer) Integer.parseInt(s)).toString());
			}
		}
		return r;
	}

	private boolean readconf(String conffilename) {
		Properties foo = new Properties();
		InputStream infile;
		try {
			infile = new FileInputStream(conffilename);
			foo.load(infile);
			host = foo.getProperty("host", host);
			service = foo.getProperty("service", service);
			username = foo.getProperty("username", username);
			password = foo.getProperty("password", password);
			if (foo.getProperty("usessl").equalsIgnoreCase("true")) {
				usessl = true;
			} else if (foo.getProperty("usessl").equalsIgnoreCase("false")) {
				usessl = false;
			}
			if (foo.getProperty("columns") != null) {
				for (String col: foo.getProperty("columns").split(","))
					columns.add(col);
			}
			if (foo.getProperty("long").equalsIgnoreCase("true")) {
				csv = false;
			} else if (foo.getProperty("long").equalsIgnoreCase("false")) {
				csv = true;
			}
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}
		return true;		
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {

		JMXProbe jp = new JMXProbe(args);
		jp.doProbe();
	}
}





