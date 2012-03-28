/*
* Peergroup - Peergroup.java
* 
* Peergroup is a P2P Shared Storage System using XMPP for data- and 
* participantmanagement and Apache Thrift for direct data-
* exchange between users.
*
* Author : Nicolas Inden
* Contact: nicolas.inden@rwth-aachen.de
*
* License: Not for public distribution!
*
* --- Mainclass ---
*/

package peergroup;

import java.io.*;
import java.net.*;
import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 * This processes cmd-line args, initializes all needed settings
 * and starts Peergroups mainloop.
 *
 * @author Nicolas Inden
 */
public class Peergroup {
	
	/**
	* @param args the command line arguments
	*/
    public static void main(String[] args) {
		// -- Handle SIGINT and SIGTERM
		SignalHandler signalHandler = new SignalHandler() {
			public void handle(Signal signal) {
			    if(Constants.caughtSignal){
			        Constants.log.addMsg("Last interrupt couldn't successfully quit the program: Using baseball bat method :-/",1);
			        System.exit(5);
			    }
				Constants.log.addMsg("Caught signal: " + signal + ". Gracefully shutting down!",1);
				Constants.caughtSignal = true;
				Constants.storage.stopStorageWorker();
				Constants.network.stopNetworkWorker();
				if(System.getProperty("os.name").equals("Linux") 
					|| System.getProperty("os.name").equals("Windows")){
					Constants.modQueue.interrupt();
				}
				Constants.main.interrupt();
			}
		};
		Signal.handle(new Signal("TERM"), signalHandler);
		Signal.handle(new Signal("INT"), signalHandler);
		
		// -- Here we go
		String os = System.getProperty("os.name");
        Constants.log.addMsg("Starting " + Constants.PROGNAME + " " 
			+ Constants.VERSION + " on " + os + " " + System.getProperty("os.version"),2);
        
        getCmdArgs(args);
		getExternalIP();
		
		// -- Create Threads
		Constants.main = new MainWorker();		
		Constants.storage = new StorageWorker();
		Constants.network = new NetworkWorker();
		// -- Start Threads
		Constants.storage.start();
		Constants.network.start();
		Constants.main.start();
		
		if(os.equals("Linux") || os.equals("Windows")){
			Constants.modQueue = new ModifyQueueWorker();
			Constants.modQueue.start();
		}
		
		// -- Wait for threads to terminate (only happens through SIGINT/SIGTERM see handler above)
		try{
			Constants.main.join();
			Constants.storage.join();
			Constants.network.join();
			if(os.equals("Linux") || os.equals("Windows")){
				Constants.modQueue.join();
			}
		}catch(InterruptedException ie){
			Constants.log.addMsg("Couldn't wait for all threads to cleanly shut down! Oh what a mess... Bye!",1);
		}
        
        Constants.log.closeLog();
    }
    
	/**
	* Parses the arguments given on the command line
	*
	* @param args the array of commands
	*/
    private static void getCmdArgs(String[] args){
		String last = "";
        for(String s: args){
            if(s.equals("-h") || s.equals("--help")){
				System.out.println(getHelpString());
				System.exit(0);
			}
			if(last.equals("-dir")){
				Constants.rootDirectory = s;
				Constants.log.addMsg("Set share directory to: " + Constants.rootDirectory,3);
			}
			if(last.equals("-jid")){
				String jid[] = s.split("@");
				if(jid.length < 2){
					Constants.log.addMsg("Invalid JID!",1);
					System.exit(1);
				}
				Constants.user = jid[0];
				Constants.server = jid[1];
				Constants.log.addMsg("Set JID to: " + Constants.user + "@" + Constants.server,3);
			}
			if(last.equals("-chan")){
				String conf[] = s.split("@");
				if(conf.length < 2){
					Constants.log.addMsg("Invalid conference channel!",1);
					System.exit(1);
				}
				Constants.conference_channel = conf[0];
				Constants.conference_server = conf[1];
				Constants.log.addMsg("Set conference channel to: " + Constants.conference_channel + "@" 
					+ Constants.conference_server,3);
			}
			if(last.equals("-XMPPport")){
				try{
					Constants.port = Integer.parseInt(s);
				}catch(NumberFormatException nan){
					Constants.log.addMsg("Invalid port!",1);
					System.exit(1);
				}
				Constants.log.addMsg("Set port to: " + Constants.port,3);
			}
			if(last.equals("-limit")){
				try{
					Constants.shareLimit = Long.parseLong(s);
				}catch(NumberFormatException nan){
					Constants.log.addMsg("Invalid share limit!",1);
					System.exit(1);
				}
				Constants.log.addMsg("Set share limit to: " + Constants.shareLimit,3);
			}
			if(last.equals("-pass")){
				Constants.pass = s;
				Constants.log.addMsg("Set pass for JID to: " + Constants.pass,3); //Maybe this should be hidden in the final version ;-)
			}
			if(last.equals("-ip")){
				Constants.ipAddress = s;
				Constants.log.addMsg("Set external IP to: " + Constants.ipAddress,3);
			}
			last = s;
        }
    }
	
	/**
	* Creates the help string containing all information of how to use this program
	*
	* @return the help string
	*/
	private static String getHelpString(){
		String out = "";
		out += "\t-h\t\t\tprints this help\n";
		out += "\t-dir\t[DIR]\t\tset the shared files directory (default: ./share/)\n";
		out += "\t-jid\t[JID]\t\tset your jabber ID (e.g. foo@jabber.bar.com)\n";
		out += "\t-pass\t[PASS]\t\tset the password for your JID\n";
		out += "\t-XMPPport\t[PORT]\tset the XMPP server port (default: 5222)\n";
		out += "\t-chan\t[CHANNEL]\tset the conference channel to join (e.g. foo@conference.jabber.bar.com)\n";
		out += "\t-ip\t[IP]\t\tmanually set your external IP (the IP is usually guessed by the program)\n";
		out += "\t-port\t[PORT]\t\tset the port for P2P data exchange (default: 43334)";
		out += "\t-limit\t[LIMIT]\t\tset the amount of space you want to share in MB (default: 2048MB)\n";
		return out;
	}
    
	/**
	* If the external IP was not set by the cmd-line argument, this function queries
	* the external IP from http://automation.whatismyip.com/n09230945.asp
	* If neither an IP was set nor one was detected, Peergroup exits.
	*/
	private static void getExternalIP(){
		if(!Constants.ipAddress.equals("")){
			Constants.log.addMsg("External IP was manually set, skipping the guessing.",2);
			return;
		}
		try{
			URL whatismyip = new URL("http://automation.whatismyip.com/n09230945.asp");
			BufferedReader in = new BufferedReader(new InputStreamReader(
			                whatismyip.openStream()));
		
			Constants.ipAddress = in.readLine();
			Constants.log.addMsg("Found external IP: " + Constants.ipAddress,2);
		}catch(Exception e){
			Constants.log.addMsg("Couldn't get external IP! " + e + " Try setting it manually!",1);
			System.exit(1);
		}
	}
}
