package ca.pinet.ExternalIPChecker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Script to check the external IP, compare to the current IP, and if there is a 
 * difference then send an email so that the new external IP can be configured
 * on the DNS server. 
 * 
 * @author Tim Pinet
 * @since May 14, 2017
 *
 */
public class ExternalIPChecker {

	private static final Logger logger = LoggerFactory.getLogger(ExternalIPChecker.class);
	
	private static final String ipifyURL = "https://api.ipify.org";
	
	private static final String extIPfile = "externalIP.txt";
	
	private static final String configFile = "config.properties";
	private static final String PROP_FROMADDRESS = "fromEmailAddress";
	private static final String PROP_TOADDRESS = "toEmailAddress";
	private static final String PROP_EMAILSMTPSERVER = "emailSMTPServer";
	private static final String PROP_EMAILSMTPPORT = "emailSMTPPort";
	private static final String PROP_EMAILUSER = "emailUser";
	private static final String PROP_EMAILPASSWORD = "emailPassword";
	
	/**
	 * Main executable for the script.
	 * 
	 * @param args
	 */
	public static void main(String[] args) {

		logger.debug("Starting check...");

		/*
		 * Load application properties
		 */
		Properties props = new Properties();
		try (InputStream input = ExternalIPChecker.class.getClassLoader().getResourceAsStream(configFile)) {
			props.load(input);
		} catch (IOException ex) {
			logger.error("Can not load appliaction configuration file '" + configFile + "'. Exiting...", ex);
			System.exit(1);
		}
		
		/*
		 * Create URL to ipify
		 */
		URL url = null;
		try {
			url = new URL(ipifyURL);
		} catch (MalformedURLException e) {
			logger.error("Can not create URL '" + ipifyURL + "'. Exiting...", e);
			System.exit(1);
		}
		
		/*
		 * Read the ipify url for the external IP
		 */
		String newExtIP = null;
		try (BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()))){
			newExtIP = br.readLine();
			logger.info("The external IP read from '" + ipifyURL + "' is " + newExtIP);
		} catch (IOException e) {
			logger.error("Cannot read new external IP from '" + ipifyURL + "'. Exiting...", e);
			System.exit(1);
		}
		
		/*
		 * Reading existing external IP from file.
		 */
		String oldExtIP = null;
		try {
			oldExtIP = new String(Files.readAllBytes(Paths.get(extIPfile)));
			logger.info("Old external IP is '" + oldExtIP + "' read from file '" + extIPfile + "'.");
		} catch (IOException e) {
			/*
			 * The file may not exist as it is the first run of the program or it got removed.
			 * Try to create the file.
			 */
			if (e instanceof NoSuchFileException) {
				logger.info("File '" + extIPfile + "' doesnt exist yet. Creating...");
				try {
					Files.write(Paths.get(extIPfile), newExtIP.getBytes());
				} catch (IOException e1) {
					logger.warn("Could not create file '" + extIPfile + "'. Will continue to operate however emails will be generated on each check until file can be created.", e1);
				}
				logger.info("File '" + extIPfile + "' created.");
			} else {
				logger.error("Could not read old IP from file '" + extIPfile + "'. Exiting...", e);
				System.exit(1);
			}
		}
		
		/*
		 * Compare IPs. If same, then do nothing. If new IP is detected, write to file and send mail.
		 */
		if (oldExtIP!=null && oldExtIP.equals(newExtIP)) {
			logger.info("Same IP as before, no problem.");
		} else {
			logger.info("New IP detected from last known. Writing to file and emailing new IP...");
			
			ExternalIPChecker.sendEmail(oldExtIP, newExtIP, props.getProperty(PROP_FROMADDRESS),props.getProperty(PROP_TOADDRESS), 
					props.getProperty(PROP_EMAILSMTPSERVER), props.getProperty(PROP_EMAILSMTPPORT), props.getProperty(PROP_EMAILUSER), props.getProperty(PROP_EMAILPASSWORD));
			
			try {
				Files.write(Paths.get(extIPfile), newExtIP.getBytes());
			} catch (IOException e1) {
				logger.warn("Could not write new ip '" + newExtIP + "' to file '" + extIPfile + "'. Will continue to operate however emails will be generated on each check until file can be created.", e1);
			}
		}
		
		logger.debug("Check completed successfully.");
	}
	
	/**
	 * Sends an email through Gmail's TLS SMTP server to notify of changed IP
	 *
	 * @param oldIP
	 * @param newIP
	 * @param fromAddress
	 * @param toAddress
	 * @param gmailUserName
	 * @param gmailPassword
	 */
	static private void sendEmail(String oldIP, String newIP, String fromAddress, String toAddress,
			String emailServer, String emailPort, String emailUserName, String emailPassword) {
		
		/*
		 * Set TLS encrypted settings for gmail
		 */
		Properties props = new Properties();
		props.put("mail.smtp.auth", "true");
		props.put("mail.smtp.starttls.enable", "true");
		props.put("mail.smtp.host", emailServer);
		props.put("mail.smtp.port", emailPort);

		/*
		 * Set the authenticator
		 */
		Session session = Session.getDefaultInstance(props,
			new javax.mail.Authenticator() {
				protected PasswordAuthentication getPasswordAuthentication() {
					return new PasswordAuthentication(emailUserName,emailPassword);
				}
			}
		);

		/*
		 * Send the email
		 */
		try {
			Message message = new MimeMessage(session);
			message.setFrom(new InternetAddress(fromAddress));
			message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toAddress));
			message.setSubject("Changed External IP");
			message.setText("External ip changed from " + oldIP + " to " + newIP);
			Transport.send(message);
		} catch (MessagingException e) {
			logger.error("Could not email new IP to '" + toAddress + "'. Exiting...", e);
			System.exit(1);
		}
		
		logger.info("Email sent successfully to '" + toAddress + "'.");
	}

}
