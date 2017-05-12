import static java.lang.System.out;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

public class TapOutUpdate {
	
	
	static Properties props = new Properties();
	static Logger logger = null;
	
	Connection conn = null,conn2 = null;
	Statement st = null,st2 = null;
	ResultSet rs = null;
	
	static boolean testMod = true;
	static String defaultMailReceiver = null;
	static String errorMailreceiver = null;
	//static String dayExecuteTime = null,workDir = null;
	
	Set<String> chtIMSIs = new HashSet<String>();
	Map<String,String> IMSIMap = new HashMap<String,String>();
	Map<String,List<Map<String,String>>> S2TIMSItoSeriveidList = new HashMap<String,List<Map<String,String>>>();
	
	List<Map<String,String>> voiceDatas = new ArrayList<Map<String,String>>();
	List<Map<String,String>> messageDatas = new ArrayList<Map<String,String>>();
	List<Map<String,String>> dataDatas = new ArrayList<Map<String,String>>();
	
	//2017-02-05 13:20:26
	SimpleDateFormat sdf = new  SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	SimpleDateFormat sdf2 = new  SimpleDateFormat("yyyyMMddHHmmss");
	
	Set<String> serviceIdNullSet = new HashSet<String>();
	int fileID = 0;
	int CDRCount = 0;
	String fileName = null;
	String fileLastModifiedTime = null;
	
	TapOutUpdate(){
		
	}
	
	TapOutUpdate(String fileName){
		logger.info("Got file name :"+fileName);
		this.fileName = fileName;
		run();
	}
	

	public static void main(String[] args) throws FileNotFoundException, IOException {
		
		loadProperties();
		initialLog4j();
		
		if(testMod){
			//args = new String[]{"Charge Detail For Export (final table)_1962.txt"};
		}
		
		if(args.length == 1){
			new TapOutUpdate(args[0]);
		}else{
			System.out.println("Input parameters are not correct.");
		}		
	}
	
	static long connectTime = 0;
	
	public void run() {
		logger.info("program runing...");
		
		try {
			chtIMSIs.clear();
			voiceDatas.clear();
			messageDatas.clear();
			dataDatas.clear();
			loadFile();
			
			createConnection();
			
			IMSIMap.clear();
			S2TIMSItoSeriveidList.clear();
			queryIMSIdatas();
			
			serviceIdNullSet.clear();
			updateDatas();
			
			String msg = "IMSIs can't find serviceId :  ";
			for(String imsi : serviceIdNullSet){
				msg+= "'"+ imsi+"',";
			}
			logger.info(msg);
			
			
			conn.commit();
			
		} catch (Exception e) {
			errorHandle(e);
			try {
				conn.rollback();
			} catch (SQLException e1) {
				errorHandle(e1);
			}
		}finally{
			
		}
		
		logger.info("program finished...");
	}
	
	
	
	public void updateDatas() throws ParseException, SQLException, ClassNotFoundException{

		logger.info("updateDatas...");
		
		if(System.currentTimeMillis()-connectTime>1000*60*20){
			reconnect();
		}

		String sql = "select TAPOUTFILE_ID.NEXTVAL ID from dual ";
		logger.info("Execute SQL:"+sql);
		rs = st.executeQuery(sql);
		
		if(!rs.next()){
			logger.info("Can't get file ID. ");
			return ;
		}
		
		fileID = rs.getInt("ID");
		
		logger.info("get file ID:"+fileID);
		
		
		logger.info("update tap out file...");
		
		sql =  "insert into TAPOUTFILE (FILEID,FILENAME,CDRCOUNT,CREATIONTIME) "
					+ "values("+fileID+",'"+fileName+"',"+CDRCount+",to_date('"+fileLastModifiedTime+"','yyyyMMddhh24miss'))";
		
		logger.info("Execute SQL:"+sql);
		st.executeUpdate(sql);
		

		int i = 0;
		logger.info("update voice datas...");
		for(Map<String,String> m : voiceDatas){
			String chtIMSI = m.get("IMSI");
			String useTime = m.get("StartTime");
			
			String[] result = getServiceId(chtIMSI,useTime);
			String serviceid = result[0];
			String s2tIMSI = result[1];

			if(serviceid == null) serviceIdNullSet.add(chtIMSI);
				
			sql = "insert into TAPOUTFILEVOICEUSAGE ("
					+ "FILEID,EVENTNO,CALLER,CALLEE,STARTTIME,"
					+ "DURATION,CHARGEUNIT,DIRECTION,IMSI,LOCATION,AMOUNT,SERVICEID) "
					+ "values("
					+ fileID+","+m.get("EventNo")+",'"+m.get("Caller")+"','"+m.get("Callee")+"',to_date('"+m.get("StartTime")+"','yyyy-MM-dd hh24:mi:ss'),"
					+ m.get("Duration")+","+m.get("ChargeUnit")+",'"+m.get("Direction")+"','"+s2tIMSI+"','"+m.get("Location")+"',"+m.get("Amount")+","+serviceid+")";			
			
			if(i==0){
				logger.info("First SQL:"+sql);
				i++;
			}
			st.addBatch(sql);
			//logger.info("HOMEIMSI/S2TIMSI/SERVICEID/useTimeD="+chtIMSI+"/"+s2tIMSI+"/"+serviceid+"/"+useTime);
		}
		st.executeBatch();
		st.clearBatch();
		
		i = 0;
		logger.info("update message datas...");
		for(Map<String,String> m : messageDatas){
			String chtIMSI = m.get("IMSI");
			String useTime = m.get("StartTime");
			
			String[] result = getServiceId(chtIMSI,useTime);
			String serviceid = result[0];
			String s2tIMSI = result[1];
			
			if(serviceid == null) serviceIdNullSet.add(chtIMSI);
			
			sql = "insert into TAPOUTFILESMSUSAGE("
					+ "FILEID,EVENTNO,CALLER,CALLEE,STARTTIME,"
					+ "DIRECTION,IMSI,LOCATION,AMOUNT,SERVICEID) "
					+ "values("
					+ ""+fileID+","+m.get("EventNo")+",'"+m.get("Caller")+"','"+m.get("Callee")+"',to_date('"+m.get("StartTime")+"','yyyy-MM-dd hh24:mi:ss'),"
					+ "'"+m.get("Direction")+"','"+s2tIMSI+"','"+m.get("Location")+"',"+m.get("Amount")+","+serviceid+")";			
			
			if(i==0){
				logger.info("First SQL:"+sql);
				i++;
			}
			
			st.addBatch(sql);
		}
		st.executeBatch();
		st.clearBatch();

		
		
		 logger.info("update data datas...");

		 i = 0;
		 
		for(Map<String,String> m : dataDatas){
			String chtIMSI = m.get("IMSI");
			String useTime = m.get("StartTime");
			
			String[] result = getServiceId(chtIMSI,useTime);
			String serviceid = result[0];
			String s2tIMSI = result[1];
			
			if(serviceid == null) serviceIdNullSet.add(chtIMSI);
			
			sql = "insert into TAPOUTFILEDATAUSAGE("
					+ "FILEID,EVENTNO,CALLER,STARTTIME,"
					+ "DURATION,UPLOADVOLUME,DOWNLOADVOLUME,CHARGEUNIT,IMSI,LOCATION,AMOUNT,SERVICEID) "
					+ "values("
					+ ""+fileID+","+m.get("EventNo")+",'"+m.get("Caller")+"',to_date('"+m.get("StartTime")+"','yyyy-MM-dd hh24:mi:ss'),"
					+ ""+m.get("Duration")+","+m.get("UploadVolume")+","+m.get("DownloadVolume")+","+m.get("ChargeUnit")+",'"+s2tIMSI+"','"+m.get("Location")+"',"+m.get("Amount")+","+serviceid+")";			
			
			if(i==0){
				logger.info("First SQL:"+sql);
				i++;
			}
			
			st.addBatch(sql);
		}
		st.executeBatch();
		st.clearBatch();
		
		logger.info("update datas finished.");
	}
	
	public String[] getServiceId(String chtIMSI,String useTime) throws ParseException{
		Date useTimeD = sdf.parse(useTime);
		String s2tIMSI = IMSIMap.get(chtIMSI);
		String serviceid = null;
		if(S2TIMSItoSeriveidList.containsKey(s2tIMSI)){
			//檢查符合條件是否為唯一
			int count = 0;
			for(Map<String,String> m: S2TIMSItoSeriveidList.get(s2tIMSI)){
				String startTime = m.get("START");
				String endTime = m.get("END");
				if(startTime == null ) continue;
				Date startTimeD = sdf2.parse(startTime);
//				logger.info(startTime);
//				logger.info(endTime);
//				logger.info(startTimeD.compareTo(useTimeD));
				if(startTimeD.compareTo(useTimeD)<=0 && (endTime == null ||"".equals(endTime) || sdf2.parse(endTime).compareTo(useTimeD)>0)){
					serviceid = m.get("SERVICEID");
					count++;
					//break;
				}
			}
			if(count>1){
				serviceid = null;
				logger.error("For IMSI "+chtIMSI+" was found more than one serviceId at time "+useTime);
			}
		}
		return new String[] {serviceid,s2tIMSI};
	}
	
	public void queryIMSIdatas() throws SQLException, ClassNotFoundException{	
		String imsis = "";
		
		int i = 0; 
		for(String imsi : chtIMSIs){
			i++;
			imsis += "'"+imsi +"',";
			
			if(i%1000==0){
				setMaps(imsis.substring(0, imsis.length()-1));
				imsis = "";
			}
		}
		if(i%1000!=0){
			setMaps(imsis.substring(0, imsis.length()-1));
		}
		
		logger.info("Total IMSI:"+i);
		
	}
	
	public void setMaps(String imsis) throws SQLException, ClassNotFoundException{
		logger.info("Map setting...");
		
		if(System.currentTimeMillis()-connectTime>1000*60*20){
			reconnect();
		}
		
		String s2tImsis = "";
		//設定HomeIMSI 與 S2TIMSI對應
		String sql = "select A.imsi,A.homeimsi,A.SERVICEID,A.CREATEDATE from imsi A where A.homeimsi in ("+imsis+")";
		logger.info("Execute SQL:"+sql);
		rs = st2.executeQuery(sql);
		while (rs.next()) {
			s2tImsis +="'"+rs.getString("imsi")+"',";
			IMSIMap.put(rs.getString("homeimsi"), rs.getString("imsi"));
		} 
		
		
		s2tImsis = s2tImsis.substring(0, s2tImsis.length()-1);
		
		if(System.currentTimeMillis()-connectTime>1000*60*20){
			reconnect();
		}
		
		//設定Service ID 使用 IMSI 的區間
/*		sql = "	Select A.IMSI,A.SERVICEID,"
				+ "to_char(A.START_TIME,'yyyyMMddhh24miss') START_TIME,"
				+ "to_char(B.END_TIME,'yyyyMMddhh24miss') END_TIME "
				+ "from (	select IMSI,START_TIME,SERVICEID "
				+ "			from (	select A.NEWVALUE IMSI,A.COMPLETEDATE START_TIME,B.SERVICEID "
				+ "						from SERVICEINFOCHANGEORDER A, SERVICEORDER B "
				+ "						WHERE A.FIELDID=3713 and A.COMPLETEDATE is not null AND A.ORDERID=B.ORDERID "
				+ "						UNION ALL "
				+ "						select A.FIELDVALUE IMSI,A.COMPLETEDATE START_TIME,A.SERVICEID "
				+ "						from NEWSERVICEORDERINFO A "
				+ "						WHERE a.fieldid=3713 and A.COMPLETEDATE is not null "
				+ "					) "
				+ "			WHERE IMSI in ("+s2tImsis+") "
				+ "		) A,"
				+ " "
				+ "		(	select A.OLDVALUE IMSI,A.COMPLETEDATE END_TIME,B.SERVICEID "
				+ "			from SERVICEINFOCHANGEORDER A, SERVICEORDER B "
				+ "			WHERE A.FIELDID=3713 and A.COMPLETEDATE is not null AND A.ORDERID=B.ORDERID AND OLDVALUE in ("+s2tImsis+") "
				+ "		) B "
				+ "WHERE A.IMSI = B.IMSI(+) AND A.SERVICEID = B.SERVICEID(+) AND A.START_TIME<B.END_TIME(+) ";*/
		
		sql = ""
				+ "select distinct A.IMSI,A.SERVICEID,A.START_TIME,NVL(A.END_TIME,B.END_TIME) END_TIME "
				+ "from(	Select A.IMSI,A.SERVICEID,to_char(A.START_TIME,'yyyyMMddhh24miss') START_TIME , to_char( min(B.END_TIME),'yyyyMMddhh24miss') END_TIME "
				+ "			from (	select IMSI,START_TIME,SERVICEID  "
				+ "						from (	select A.NEWVALUE IMSI,A.COMPLETEDATE START_TIME,B.SERVICEID  "
				+ "									from SERVICEINFOCHANGEORDER A, SERVICEORDER B "
				+ "									WHERE A.FIELDID=3713 and A.COMPLETEDATE is not null AND A.ORDERID=B.ORDERID  "
				+ "									UNION ALL  "
				+ "									select A.FIELDVALUE IMSI,A.COMPLETEDATE START_TIME,A.SERVICEID  "
				+ "									from NEWSERVICEORDERINFO A "
				+ "									WHERE a.fieldid=3713 and A.COMPLETEDATE is not null "
				+ "								) "
				+ "						WHERE IMSI in ("+s2tImsis+") "
				+ "					) A,  "
				+ "					(	select A.OLDVALUE IMSI,A.COMPLETEDATE END_TIME,B.SERVICEID "
				+ "						from SERVICEINFOCHANGEORDER A, SERVICEORDER B "
				+ "						WHERE A.FIELDID=3713 and A.COMPLETEDATE is not null AND A.ORDERID=B.ORDERID AND OLDVALUE in ("+s2tImsis+") "
				+ "					) B "
				+ "			WHERE A.IMSI = B.IMSI(+) AND A.SERVICEID = B.SERVICEID(+) AND A.START_TIME<B.END_TIME(+) "
				+ "			GROUP BY A.IMSI,A.SERVICEID,A.START_TIME "
				+ "		) A ,"
				+ "		(	select serviceid,to_char(datecanceled,'yyyyMMddhh24miss')  END_TIME "
				+ "			from service "
				+ "			where datecanceled is not null "
				+ "		) B "
				+ "where A.serviceid = B.serviceid(+) ";
		
		logger.info("Execute SQL:"+sql);
		rs = st2.executeQuery(sql);
		while (rs.next()) {
			Map<String,String> m = new HashMap<String,String>();
			m.put("SERVICEID", rs.getString("SERVICEID"));
			m.put("START",rs.getString("START_TIME"));
			m.put("END",rs.getString("END_TIME"));
			
			List<Map<String,String>> list = null;
			String s2tImsi = rs.getString("IMSI");
			
			if(S2TIMSItoSeriveidList.containsKey(s2tImsi))
				list = S2TIMSItoSeriveidList.get(s2tImsi);
			else
				list = new ArrayList<Map<String,String>>();
			
			list.add(m);
			S2TIMSItoSeriveidList.put(s2tImsi, list);
		} 
		logger.info("Map setting end.");
	}

	
	private void loadFile() throws Exception{
		logger.info("loadFile...  ");
		
		File f = new File(fileName);
		
		Map<String,Integer> columnMap = new HashMap<String,Integer>();
		BufferedReader reader = null;
		fileLastModifiedTime = sdf2.format(new Date(f.lastModified()));
		try {
			reader = new BufferedReader(new InputStreamReader(new FileInputStream(f)));  
			String str;
			
			//設定column Map
			if((str = reader.readLine()) != null){
				String[] column = str.split("\t");
				for(int col = 0 ; col < column.length ; col++){
					columnMap.put(column[col], col);
				}
			}

			while ((str = reader.readLine()) != null) {
				CDRCount++;
				String [] data = str.split("\t");
				
				String chargedItem = data[columnMap.get("Charged Item")].replaceAll("'", "");

				String 	IMSI = data[columnMap.get("IMSI")].replaceAll("'", ""); //目標是S2T IMSI
				chtIMSIs.add(IMSI);
				
				String 	Caller = null;
				String 	Callee = null;
				String 	ChargeUnit = null;
				
				String 	EventNo = data[columnMap.get("EventNo")];
				String 	Amount = data[columnMap.get("Charge")];
				String 	StartTime = data[columnMap.get("Charge TS")].replaceAll("'", "");
				String 	Duration = data[columnMap.get("Duration")];
				String 	Location = data[columnMap.get("Operator Spec. Info")].replaceAll("'", "");
				
				 if("X".equalsIgnoreCase(chargedItem)){
					//數據
					Caller = data[columnMap.get("Msisdn")].replaceAll("'", "");
					
					
					
					ChargeUnit = new BigDecimal(Double.valueOf(data[columnMap.get("Chargeable Units")])/1024).setScale(0, BigDecimal.ROUND_CEILING).toString();
					
					//ChargeUnit = String.valueOf(Math.ceil(Double.valueOf(data[columnMap.get("Chargeable Units")])/1024));
					String UploadVolume = data[columnMap.get("Data Volume Outgoing")].replaceAll("'", "");
					String DownloadVolume = data[columnMap.get("Data Volume Incoming")].replaceAll("'", "");
					 
					//logger.info(EventNo+","+Caller+","+StartTime+","+Duration+"."+UploadVolume+","+DownloadVolume+","+ChargeUnit+","+IMSI+","+Location+","+Amount);

					Map<String,String> m = new HashMap<String,String>();
					m.put("EventNo", EventNo);
					m.put("Caller", Caller);
					m.put("StartTime", StartTime);
					m.put("Duration", Duration);
					m.put("UploadVolume", UploadVolume);
					m.put("DownloadVolume", DownloadVolume);
					m.put("ChargeUnit", ChargeUnit);
					m.put("IMSI", IMSI);
					m.put("Location", Location);
					m.put("Amount", Amount);
					dataDatas.add(m);
				 }else{
					 String Direction = data[columnMap.get("Path")];
					 
					 if(Direction.indexOf("MTC")!=-1){
							Direction = "T";
							Caller =  data[columnMap.get("Phone Number")].replaceAll("'", "");
							Callee = data[columnMap.get("Msisdn")].replaceAll("'", "");
						}else if(Direction.indexOf("MOC")!=-1){
							Direction = "O";
							Caller =  data[columnMap.get("Msisdn")].replaceAll("'", "");
							Callee = data[columnMap.get("Phone Number")].replaceAll("'", "");
						}else{
							throw new Exception(fileName+"'s path data error.");
						}
					 
					 if("D".equalsIgnoreCase(chargedItem)){
						 
						//語音								 
						ChargeUnit = new BigDecimal(Double.valueOf(data[columnMap.get("Chargeable Units")])/60).setScale(0, BigDecimal.ROUND_CEILING).toString();
						//ChargeUnit = String.valueOf(Math.ceil(Double.valueOf(data[columnMap.get("Chargeable Units")])/60));
						//logger.info(EventNo+","+Caller+","+Callee+","+StartTime+","+Duration+"."+ChargeUnit+","+Direction+","+IMSI+","+Location+","+Amount);
						
						Map<String,String> m = new HashMap<String,String>();
						m.put("EventNo", EventNo);
						m.put("Caller", Caller);
						m.put("Callee", Callee);
						m.put("StartTime", StartTime);
						m.put("Duration", Duration);
						m.put("ChargeUnit", ChargeUnit);
						m.put("Direction", Direction);
						m.put("IMSI", IMSI);
						m.put("Location", Location);
						m.put("Amount", Amount);
						voiceDatas.add(m);
						 
						}else if("E".equalsIgnoreCase(chargedItem)){
							//簡訊
							//logger.info(EventNo+","+Caller+","+Callee+","+StartTime+","+Direction+","+IMSI+","+Location+","+Amount);
							
							Map<String,String> m = new HashMap<String,String>();
							m.put("EventNo", EventNo);
							m.put("Caller", Caller);
							m.put("Callee", Callee);
							m.put("StartTime", StartTime);
							m.put("Direction", Direction);
							m.put("IMSI", IMSI);
							m.put("Location", Location);
							m.put("Amount", Amount);
							messageDatas.add(m);
						}	 
				 }
			} 
			 logger.info("CDRCount:"+CDRCount);
		} finally {
			if(reader!=null){
				reader.close();
			}
		}
	}
		
	static void loadProperties() throws FileNotFoundException, IOException{
		System.out.println("loadProperties...");
		String path="Log4j.properties";
		props.load(new   FileInputStream(path));
		PropertyConfigurator.configure(props);
		
		testMod = !"false".equals(props.getProperty("TestMod").trim());
		defaultMailReceiver = props.getProperty("DefaultMailReceiver").trim();
		errorMailreceiver = props.getProperty("ErrorMailReceiver").trim();
		//dayExecuteTime = props.getProperty("dayExecuteTime").trim();
		//workDir = props.getProperty("workdir").trim();

		System.out.println("loadProperties Success!");
	}
	
	static void initialLog4j(){
		logger =Logger.getLogger(TapOutUpdate.class);
		
		logger.info("testMod:"+testMod);
		//logger.info("dayExecuteTime:"+dayExecuteTime);
		logger.info("errorMailreceiver:"+errorMailreceiver);
		logger.info("Logger Load Success!");
	}
	
	void createConnection() throws SQLException, ClassNotFoundException{
		logger.info("createConnection...");
		connectTime = System.currentTimeMillis();
		
		String url,DriverClass,UserName,PassWord;
		
		url=props.getProperty("Oracle.URL")
				.replace("{{Host}}", props.getProperty("Oracle.Host"))
				.replace("{{Port}}", props.getProperty("Oracle.Port"))
				.replace("{{ServiceName}}", (props.getProperty("Oracle.ServiceName")!=null?props.getProperty("Oracle.ServiceName"):""))
				.replace("{{SID}}", (props.getProperty("Oracle.SID")!=null?props.getProperty("Oracle.SID"):""));
		
		DriverClass = props.getProperty("Oracle.DriverClass");
		UserName = props.getProperty("Oracle.UserName");
		PassWord = props.getProperty("Oracle.PassWord");
		
		Class.forName(DriverClass);
		conn = DriverManager.getConnection(url, UserName, PassWord);
		conn.setAutoCommit(false);
		st = conn.createStatement();
		

		
		url=props.getProperty("mBOSS.URL")
				.replace("{{Host}}", props.getProperty("mBOSS.Host"))
				.replace("{{Port}}", props.getProperty("mBOSS.Port"))
				.replace("{{ServiceName}}", (props.getProperty("mBOSS.ServiceName")!=null?props.getProperty("mBOSS.ServiceName"):""))
				.replace("{{SID}}", (props.getProperty("mBOSS.SID")!=null?props.getProperty("mBOSS.SID"):""));
		
		DriverClass = props.getProperty("mBOSS.DriverClass");
		UserName = props.getProperty("mBOSS.UserName");
		PassWord = props.getProperty("mBOSS.PassWord");
		
		Class.forName(DriverClass);
		conn2 = DriverManager.getConnection(url, UserName, PassWord);
		st2 = conn2.createStatement();
		
		logger.info("createConnection Success!");
	}
	
	void closeConnection(){
		logger.info("closeConnection...");
		if(rs!=null)
			try {
				rs.close();
			} catch (SQLException e) {	}
		
		if(st!=null)
			try {
				st.close();
			} catch (SQLException e) {
			}

		if(conn!=null)
			try {
				conn.close();
			} catch (SQLException e) {
			}	
		
		if(st2!=null)
			try {
				st2.close();
			} catch (SQLException e) {
			}

		if(conn2!=null)
			try {
				conn2.close();
			} catch (SQLException e) {
			}	
		
		rs = null;
		st = null;
		conn = null;
		st2=null;
		conn2 = null;
		
		logger.info("closeConnection Success!");
	}
	
	public void reconnect() throws SQLException, ClassNotFoundException{
		closeConnection();
		createConnection();
	}
	
	void errorHandle(Exception e){
		errorHandle(null,e);
	}
	
	void errorHandle(String content,Exception e){
		String errorMsg = null;
		if(e!=null){
			logger.error(content, e);
			StringWriter s = new StringWriter();
			e.printStackTrace(new PrintWriter(s));
			errorMsg=s.toString();
		}
		//sendErrorMail(content+"\n"+errorMsg);
	}
	
	/*void sendErrorMail(String content){
		sendMail(content,errorMailreceiver);
	}*/
	
	/*void sendMail(String mailContent,String mailReceiver){
		String mailSubject="sendLandingSMS Error";
		String mailSender="LandingSMS_Server";
		
		String [] cmd=new String[3];
		cmd[0]="/bin/bash";
		cmd[1]="-c";
		cmd[2]= "/bin/echo \""+mailContent+"\" | /bin/mail -s \""+mailSubject+"\" -r "+mailSender+" "+mailReceiver;

		try{
			Process p = Runtime.getRuntime().exec (cmd);
			p.waitFor();
			if(logger!=null)
				logger.info("send mail cmd:"+cmd[2]);
			System.out.println("send mail cmd:"+cmd[2]);
		}catch (Exception e){
			if(logger!=null)
				logger.info("send mail fail:"+cmd[2]);
			System.out.println("send mail fail:"+cmd[2]);
		}
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
		}
	}*/
	
	/*void sleep(long time){
		try {
			Thread.sleep(time);
		} catch (InterruptedException e) {	}
	}*/
	

}
