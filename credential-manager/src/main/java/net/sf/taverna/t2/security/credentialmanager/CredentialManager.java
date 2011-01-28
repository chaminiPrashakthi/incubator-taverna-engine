/*******************************************************************************
 * Copyright (C) 2008-2010 The University of Manchester   
 * 
 *  Modifications to the initial code base are copyright of their
 *  respective authors, or their employers as appropriate.
 * 
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2.1 of
 *  the License, or (at your option) any later version.
 *    
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *    
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 ******************************************************************************/
package net.sf.taverna.t2.security.credentialmanager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.Key;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

import net.sf.taverna.t2.lang.observer.MultiCaster;
import net.sf.taverna.t2.lang.observer.Observable;
import net.sf.taverna.t2.lang.observer.Observer;
import net.sf.taverna.t2.spi.SPIRegistry;

import org.apache.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Provides a wrapper for Taverna's Keystore and Truststore and implements
 * methods for managing user's credentials (passwords, private/proxy key pairs) 
 * and trusted services and CAs' public key certificates.
 * 
 * Keystore and Truststore are standard JCEKS-type keystores.
 * 
 * @author Alex Nenadic
 * @author Stian Soiland-Reyes
 */

public class CredentialManager implements Observable<KeystoreChangedEvent> {

	public class ClearCachesObserver implements Observer<KeystoreChangedEvent> {
		public void notify(Observable<KeystoreChangedEvent> sender,
				KeystoreChangedEvent message) throws Exception {
			synchronized (Security.class) {
				cachedServiceMap = null;
				cachedServiceURIs = null;
			}
		}
	}

	private static final String UTF_8 = "UTF-8";

	private static final String PROPERTY_TRUSTSTORE = "javax.net.ssl.trustStore";
	private static final String PROPERTY_TRUSTSTORE_PASSWORD = "javax.net.ssl.trustStorePassword";
	private static final String PROPERTY_KEYSTORE = "javax.net.ssl.keyStore";
	private static final String PROPERTY_KEYSTORE_PASSWORD = "javax.net.ssl.keyStorePassword";
	private static final String PROPERTY_KEYSTORE_TYPE = "javax.net.ssl.keyStoreType";
	private static final String PROPERTY_KEYSTORE_PROVIDER = "javax.net.ssl.keyStoreProvider";
	private static final String PROPERTY_TRUSTSTORE_TYPE = "javax.net.ssl.trustStoreType";
	private static final String PROPERTY_TRUSTSTORE_PROVIDER = "javax.net.ssl.trustStoreProvider";

	// Various passwords to try for the Java's default truststore.
	public static List<String> defaultTrustStorePasswords = Arrays.asList(
			System.getProperty(PROPERTY_TRUSTSTORE_PASSWORD, ""), "changeit",
			"changeme", "");

	public static final String T2TRUSTSTORE_FILE = "t2truststore.jceks";
	//public static final String SERVICE_URLS_FILE = "t2serviceURLs.txt";
	public static final String T2KEYSTORE_FILE = "t2keystore.jceks";
	
	// ASCII NUL character - for separating the username from the rest of the string 
	// when saving it in the Keystore. Seems like a good separator as it will highly 
	// unlikely feature in a username.
	public static final char USERNAME_AND_PASSWORD_SEPARATOR_CHARACTER = '\u0000';

	private static Logger logger = Logger.getLogger(CredentialManager.class);

	// Multicaster of KeystoreChangedEventS
	private MultiCaster<KeystoreChangedEvent> multiCaster = new MultiCaster<KeystoreChangedEvent>(
			this);

	private HashMap<URI, URI> cachedServiceMap = null;

	private ClearCachesObserver cachedServiceMapObserver = new ClearCachesObserver();

	private List<URI> cachedServiceURIs = null;

	// A directory containing Credential Manager's Keystore/Truststore/etc. files.
	private static File credentialManagerDirectory = null;
	
	// Master password for Credential Manager - used to create/access the Keystore and Truststore.
	private static String masterPassword;
	
	// Keystore file
	private static File keystoreFile = null;
	
	// Truststore file
	private static File truststoreFile = null;
	
	// Service URLs file containing lists of service URLs associated with
	// private key aliases. The alias points to the key pair entry to be used
	// for a particular service.
	//private static File serviceURLsFile = null;

	// Keystore containing user's passwords and private keys with corresponding public key certificate chains.
	private static KeyStore keystore;
	
	// Truststore containing trusted certificates of CA authorities and services (servers).
	private static KeyStore truststore;

	// A map of service URLs associated with private key aliases, i.e. aliases
	// are keys in the hashmap and lists of URLs are hashmap values.
	//private static HashMap<String, ArrayList<String>> serviceURLsForKeyPairs;
	
	// Constants denoting which of the two keystores (Keystore or Truststore) we are currently performing
	// an operation on.
	public static final String KEYSTORE = "Keystore";

	public static final String TRUSTSTORE = "Truststore";
	
	// Default password for Truststore - needed as the Truststore needs to be
	// populated before the Workbench starts up to initiate the SSLSocketFactory
	// and to avoid popping up a dialog to ask the user for it.
	//private static final String TRUSTSTORE_PASSWORD = "Tu/Ap%2_$dJt6*+Rca9v";

	// Whether SSLSocketFactory has been initialised with Taverna's Keystore/Truststore.
	private static boolean sslInitialised = false;
	
	// Credential Manager singleton
	private static CredentialManager INSTANCE;

	/**
	 * Return a CredentialManager singleton.
	 */
	public static synchronized CredentialManager getInstance()
			throws CMException {
		if (INSTANCE == null) {
			INSTANCE = new CredentialManager();
		}
		return INSTANCE;
	}

	/**
	 * Return a Credential Manager singleton for a given master password.
	 * This should really only be used from CredentialManagerUI where we want to
	 * allow user to cancel entering the password (which only results in the
	 * CredentialManagerUI dialog not being shown), so we have to manage
	 * obtaining the password ourselves. Otherwise, Credential Manager itself
	 * takes care of getting the password from the user using password
	 * providers. If a user cancels inside a password provider that should cause
	 * an error.
	 */
	public static synchronized CredentialManager getInstance(
			String masterPassword) throws CMException {
		if (INSTANCE == null) {
			INSTANCE = new CredentialManager(masterPassword);
		} else {
			if (!confirmMasterPassword(masterPassword)) {
				String exMessage = "Incorrect master password for Credential Manager.";
				throw new CMException(exMessage);
			}
		}
		return INSTANCE;
	}
	
	/**
	 * Return a Credential Manager singleton for a given master password and a 
	 * path to a directory where to find the relevant Keystore/Trustore/etc. files.
	 * This constructor is used if you want Credential Manager to read the files 
	 * from a location different from the default one (which for the Workbench is 
	 * in <TAVERNA_HOME>/security).
	 */
	public static synchronized CredentialManager getInstance(String credentialManagerDirPath, 
			String masterPassword) throws CMException {
		if (INSTANCE == null) {
			INSTANCE = new CredentialManager(credentialManagerDirPath, masterPassword);
		}
		return INSTANCE;
	}

	public static synchronized CredentialManager getInstanceIfInitialized() {
		return INSTANCE;
	}

	/**
	 * Override the Object's clone method to prevent the singleton object being cloned.
	 */
	@Override
	public Object clone() throws CloneNotSupportedException {
		throw new CloneNotSupportedException();
	}

	/**
	 * Credential Manager's constructor.
	 * 
	 * @see #getInstance()
	 * @see #getInstance(String)
	 * @see #getInstanceIfInitialized()
	 */
	private CredentialManager() throws CMException {
		
		// Open the files stored in the (DEFAULT!!!) Credential Manager's directory		
		loadDefaultConfigurationFiles();	
		masterPassword = getMasterPassword();
		init();
	}

	static SPIRegistry<CredentialProviderSPI> masterPasswordProviderSPI = new SPIRegistry<CredentialProviderSPI>(
			CredentialProviderSPI.class);

	private String getMasterPassword() throws CMException {

		if (keystoreFile == null){
			loadDefaultConfigurationFiles();
		}
		
		boolean firstTime = !keystoreFile.exists();

		List<CredentialProviderSPI> masterPasswordProviders = findMasterPasswordProviders();
		for (CredentialProviderSPI provider : masterPasswordProviders) {
			if (!provider.canProvideMasterPassword()) {
				continue;
			}
			String password = provider.getMasterPassword(firstTime);
			if (password != null) {
				return password;
			}
		}
		
		// We are in big trouble - we do not have a single master password provider.
		String exMessage = "Failed to obtain master password from providers: "
				+ masterPasswordProviders;
		logger.error(exMessage);
		throw new CMException(exMessage);
	}

	private static List<CredentialProviderSPI> findMasterPasswordProviders() {
		List<CredentialProviderSPI> masterPasswordProviders = masterPasswordProviderSPI
				.getInstances();
		Collections.sort(masterPasswordProviders,
				new Comparator<CredentialProviderSPI>() {
					public int compare(CredentialProviderSPI o1,
							CredentialProviderSPI o2) {
						// Reverse sort - highest provider first
						return o2.getProviderPriority()
								- o1.getProviderPriority();
					}
				});
		return masterPasswordProviders;
	}

	/**
	 * Credential Manager's constructor for a given master password.
	 */
	private CredentialManager(String password) throws CMException {
		
		// Open the files stored in the (DEFAULT!!!) Credential Manager's directory		
		loadDefaultConfigurationFiles();	
		masterPassword = password;
		init();
	}

	/**
	 * Credential Manager's constructor for a given master password and a directory where 
	 * Credential Manager's Keystore/Truststore/etc. files are located.
	 */
	private CredentialManager(String credentialManagerDirPath, String password) throws CMException {
		
		// Open the files stored in the Credential Manager's directory passed in
		loadConfigurationFiles(credentialManagerDirPath);
		masterPassword = password;
		// Load the files
		init();
	}
	
	/**
	 * Initialise Credential Manager - load the Keystore and Truststore.
	 */
	private void init() throws CMException {

		this.addObserver(cachedServiceMapObserver);

		// Make sure we have BouncyCastle provider installed, just in case (needed for some tests and reading PKCS#12 keystores)
        Security.addProvider(new BouncyCastleProvider()); 
		
		// Load the Keystore
		try {
			loadKeystore();
			logger.info("Credential Manager: Loaded the Keystore.");
		} catch (CMException cme) {
			//logger.error(cme.getMessage(), cme);
			throw cme;
		}

//		// Load service URLs associated with private key aliases from a file
//		try {
//			loadServiceURLsForKeyPairs();
//			logger.info("Credential Manager: Loaded the Service "
//					+ "URLs for private key pairs.");
//		} catch (CMException cme) {
//			logger.error(cme.getMessage(), cme);
//			throw cme;
//		}

		// Load the Truststore
		try {
			loadTruststore();
			logger.info("Credential Manager: Loaded the Truststore.");
		} catch (CMException cme) {
			//logger.error(cme.getMessage(), cme);
			throw cme;
		}
	}

	/**
	 * Load Taverna's JCEKS-type Keystore from a file on the disk.
	 */
	protected static void loadKeystore()
			throws CMException {

		if (keystore == null){
			try {
				/*
				 * Try to create the Taverna's Keystore - has to be JKS- or JCEKS-type
				 * keystore because we use it to set the system property
				 * "javax.net.ssl.keyStore"
				 */
				keystore = KeyStore.getInstance("JCEKS");
			} catch (Exception ex) {
				// The requested keystore type is not available from security
				// provider
				String exMessage = "Failed to instantiate Taverna's 'JCEKS'-type Keystore.";
				//logger.error(exMessage, ex);
				throw new CMException(exMessage, ex);
			}

			if (keystoreFile.exists()) { // If the file exists, open it

				// Try to load the Keystore
				FileInputStream fis = null;
				try {
					// Get the file
					fis = new FileInputStream(keystoreFile);
					// Load the Keystore from the file
					keystore.load(fis, masterPassword.toCharArray());
				} catch (Exception ex) {
					String exMessage = "Failed to load Taverna's Keystore. Possible reason: incorrect password or corrupted file.";
					logger.error(exMessage, ex);
					throw new CMException(exMessage, ex);
				} finally {
					if (fis != null) {
						try {
							fis.close();
						} catch (IOException e) {
							// ignore
						}
					}
				}
			} else {
				// Otherwise create a new empty Keystore
				FileOutputStream fos = null;
				try {
					keystore.load(null, null);
					// Immediately save the new (empty) Keystore to the file
					fos = new FileOutputStream(keystoreFile);
					keystore.store(fos, masterPassword.toCharArray());
				} catch (Exception ex) {
					String exMessage = "Failed to generate a new empty Keystore.";
					//logger.error(exMessage, ex);
					throw new CMException(exMessage, ex);
				} finally {
					if (fos != null) {
						try {
							fos.close();
						} catch (IOException e) {
							// ignore
						}
					}
				}
			}

			/*
			 * Taverna distro for MAC contains info.plist file with some Java
			 * system properties set to use the Keychain which clashes with what
			 * we are setting here so we need to clear them
			 */
			//System.clearProperty(PROPERTY_KEYSTORE_TYPE); // "javax.net.ssl.keyStoreType"
			System.clearProperty(PROPERTY_KEYSTORE_PROVIDER); // "javax.net.ssl.keyStoreProvider"

			/*
			 * Not quite sure why we still need to set these two properties
			 * since we are creating our own SSLSocketFactory with our own
			 * KeyManager that uses Taverna's Keystore, but seem like after
			 * Taverna starts up and the first time it needs SSLSocketFactory
			 * for HTTPS connection it is still using the default Java's
			 * keystore unless these properties are set. Set the system property
			 * "javax.net.ssl.keystore" to use Taverna's keystore.
			 */
			// Axis 1 likes reading from these properties but seems to work as well with 
			// Taverna's SSLSocetFactory as well. We do not want to expose these
			// as they can be read from Beanshells.
			//System.setProperty(PROPERTY_KEYSTORE,
			//		keystoreFile.getAbsolutePath());
			//System.setProperty(PROPERTY_KEYSTORE_PASSWORD, masterPassword);
			System.setProperty(PROPERTY_KEYSTORE_TYPE, "JCEKS");
		}
	}

	/**
	 * Load Taverna's Truststore from a file on a disk. If the Truststore does
	 * not already exist, a new empty one will be created and contents of Java's
	 * truststore located in <JAVA_HOME>/lib/security/cacerts will be copied
	 * over to the Truststore.
	 */
	private static void loadTruststore()
			throws CMException {

		if (truststore == null) { // since truststore can also be initialised
									// from initaliseSSL() method, check if it
									// already is
			/*
			 * Try to create the Taverna's Truststore - has to be JKS- or JCEKS-type
			 * keystore because we use it to set the system property
			 * "javax.net.ssl.trustStore"
			 */
			try {
				truststore = KeyStore.getInstance("JCEKS");
			} catch (Exception ex) {
				/*
				 * The requested keystore type is not available from the
				 * provider
				 */
				String exMessage = "Failed to instantiate Taverna's 'JCEKS'-type Truststore.";
				//logger.error(exMessage, ex);
				throw new CMException(exMessage, ex);
			}

			if (truststoreFile.exists()) {
				// If the Truststore file already exists, open it and load the
				// Truststore
				FileInputStream fis = null;
				try {
					// Get the file
					fis = new FileInputStream(truststoreFile);
					// Load the Truststore from the file
					truststore.load(fis, masterPassword.toCharArray());
				} catch (Exception ex) {
					String exMessage = "Failed to load Taverna's Truststore. Possible reason: incorrect password or corrupted file.";
					//logger.error(exMessage, ex);
					throw new CMException(exMessage, ex);
				} finally {
					if (fis != null) {
						try {
							fis.close();
							fis = null;
						} catch (IOException e) {
							// ignore
						}
					}
				}
			} else {
				/*
				 * Otherwise create a new empty Truststore and load it with
				 * certs from Java's truststore.
				 */
				File javaTruststoreFile = new File(System
						.getProperty("java.home")
						+ "/lib/security/cacerts");
				KeyStore javaTruststore = null;
				// Java's truststore is of type "JKS" - try to load it
				try {
					javaTruststore = KeyStore.getInstance("JKS");
				} catch (Exception ex) {
					// The requested keystore type is not available from the
					// provider
					String exMessage = "Failed to instantiate a 'JKS'-type keystore for reading Java's truststore.";
					//logger.error(exMessage, ex);
					throw new CMException(exMessage, ex);
				}

				FileInputStream fis = null;
				boolean loadedJavaTruststore = false;
				/*
				 * Load Java's truststore from the file - try with the default
				 * Java truststore passwords.
				 */
				for (String password : defaultTrustStorePasswords) {
					logger.info("Trying to load Java truststore using password: " + password);
					try {
						// Get the file
						fis = new FileInputStream(javaTruststoreFile);
						javaTruststore.load(fis, password.toCharArray());
						loadedJavaTruststore = true;
						break;
					} catch (IOException ioex) {
						/*
						 * If there is an I/O or format problem with the
						 * keystore data, or if the given password was incorrect
						 * (Thank you Sun, now I can't know if it is the file or
						 * the password..)
						 */
						String message = "Failed to load the Java "
								+ "truststore to copy "
								+ "over certificates using default password: "
								+ password + " from " + javaTruststoreFile;
						logger.info(message);
					} catch (NoSuchAlgorithmException e) {
						logger.error("Unknown encryption algorithm "
								+ "while loading Java truststore from "
								+ javaTruststoreFile, e);
						break;
					} catch (CertificateException e) {
						logger.error("Certificate error while "
								+ "loading Java truststore from "
								+ javaTruststoreFile, e);
						break;
					} finally {
						if (fis != null) {
							try {
								fis.close();
							} catch (IOException e) {
								logger.warn("Could not close input stream to "
										+ javaTruststoreFile, e);
							}
						}
					}
				}

				if (!loadedJavaTruststore) {
					// Try using SPIs (typically pop up GUI)
					if (!(copyPasswordFromGUI(javaTruststore,
							javaTruststoreFile))) {
						String error = "Credential manager failed to load"
								+ " certificates from Java's truststore.";
						String help = "Try using the system property -D"
								+ PROPERTY_TRUSTSTORE_PASSWORD
								+ "=TheTrustStorePassword";
						logger.error(error + " " + help);
						System.err.println(error);
						System.err.println(help);
					}
				}

				FileOutputStream fos = null;
				// Create a new empty Truststore for Taverna
				try {
					truststore.load(null, null);
					if (loadedJavaTruststore) {
						// Copy certificates into Taverna's Truststore from
						// Java's truststore.
						Enumeration<String> aliases = javaTruststore.aliases();
						while (aliases.hasMoreElements()) {
							String alias = aliases.nextElement();
							Certificate certificate = javaTruststore
									.getCertificate(alias);
							if (certificate instanceof X509Certificate) {
								String trustedCertAlias = createX509CertificateAlias((X509Certificate) certificate);
								truststore.setCertificateEntry(
										trustedCertAlias, certificate);
							}
						}
					}
					// Immediately save the new Truststore to the file
					fos = new FileOutputStream(truststoreFile);
					truststore.store(fos, masterPassword.toCharArray());
				} catch (Exception ex) {
					String exMessage = "Failed to generate new empty Taverna's Truststore.";
					//logger.error(exMessage, ex);
					throw new CMException(exMessage, ex);
				} finally {
					if (fos != null) {
						try {
							fos.close();
						} catch (IOException e) {
							// ignore
						}
					}
				}
			}

			/*
			 * Taverna distro for MAC contains info.plist file with some Java
			 * system properties set to use the Keychain which clashes with what
			 * we are setting here so we need to clear them.
			 */
			//System.clearProperty(PROPERTY_TRUSTSTORE_TYPE_MACOS); // "javax.net.ssl.trustStoreType"
			System.clearProperty(PROPERTY_TRUSTSTORE_PROVIDER);// "javax.net.ssl.trustStoreProvider"

			/*
			 * Not quite sure why we still need to set these two properties
			 * since we are creating our own SSLSocketFactory with our own
			 * TrustManager that uses Taverna's Truststore, but seem like after
			 * Taverna starts up and the first time it needs SSLSocketFactory
			 * for HTTPS connection it is still using the default Java's
			 * truststore unless these properties are set. Set the system
			 * property "javax.net.ssl.Truststore" to use Taverna's truststore.
			 */

			// Axis 1 likes reading from these properties but seems to work as well with 
			// Taverna's SSLSocetFactory as well. We do not want to expose these
			// as they can be read from Beanshells.
			//System.setProperty(PROPERTY_TRUSTSTORE, truststoreFile
			//		.getAbsolutePath());
			//System.setProperty(PROPERTY_TRUSTSTORE_PASSWORD, masterPassword);
			System.setProperty(PROPERTY_TRUSTSTORE_TYPE, "JCEKS"); // "javax.net.ssl.trustStoreType"

			/*
			 * HttpsURLConnection
			 * .setDefaultSSLSocketFactory(createTavernaSSLSocketFactory());
			 * 
			 * sslInitialised = true;
			 */

		}	
	}

	private static boolean copyPasswordFromGUI(KeyStore javaTruststore,
			File javaTruststoreFile) {
		List<CredentialProviderSPI> masterPasswordProviders = findMasterPasswordProviders();
		String javaTruststorePassword = null;
		for (CredentialProviderSPI provider : masterPasswordProviders) {
			if (!provider.canProvideJavaTruststorePassword()) {
				continue;
			}
			javaTruststorePassword = provider.getJavaTruststorePassword();
			if (javaTruststorePassword == null) {
				continue;
			}
			FileInputStream fis = null;
			try {
				fis = new FileInputStream(javaTruststoreFile);
				javaTruststore.load(fis, javaTruststorePassword.toCharArray());
				return true;
			} catch (Exception ex) {
				String exMessage = "Failed to load the Java truststore to copy over certificates"
						+ " using user-provided password from spi " + provider;
				logger.warn(exMessage, ex);
				return false;
			} finally {
				if (fis != null) {
					try {
						fis.close();
					} catch (IOException e) {
						// ignore
					}
				}
			}
		}
		String exMessage = "None (if any) MasterPasswordProviderSPI could unlock "
				+ "Java's truststore. Creating a new empty "
				+ "Truststore for Taverna.";
		logger.error(exMessage);
		return false;
	}

	/**
	 * Get a username and password pair for the given service, or null if it
	 * does not exit. The returned array contains username as the first element
	 * and password as the second.
	 * 
	 * @deprecated Use
	 *             {@link #getUsernameAndPasswordForService(URI, boolean, String)}
	 *             instead
	 */
	@Deprecated
	public String[] getUsernameAndPasswordForService(String serviceURL)
			throws CMException {
		UsernamePassword usernamePassword = getUsernameAndPasswordForService(
				URI.create(serviceURL), false, null);
		if (usernamePassword == null) {
			return null;
		}
		String[] pair = new String[2];
		pair[0] = usernamePassword.getUsername();
		pair[1] = String.valueOf(usernamePassword.getPassword());
		usernamePassword.resetPassword();
		return pair;
	}

	/**
	 * Get a username and password pair for the given service, or null if it
	 * does not exit.
	 * <p>
	 * If the credentials are not available in the Keystore, it will
	 * invoke SPI implementations of the {@link UsernamePasswordProviderSPI}
	 * interface for asking the user (typically through the UI) or resolving
	 * hard-coded credentials.
	 * <p>
	 * If the parameter <code>usePathRecursion</code> is true, then the
	 * credential manager will also attempt to look for stored credentials for
	 * each of the parents in the URI.
	 * 
	 * @param serviceURI
	 * @param usePathRecursion
	 * @param requestingPrompt
	 * @return
	 * @throws CMException
	 */
	public UsernamePassword getUsernameAndPasswordForService(URI serviceURI,
			boolean usePathRecursion, String requestingPrompt)
			throws CMException {

		synchronized (keystore) {
			/* Alias for the username and password entry */
			SecretKeySpec passwordKey = null;
			LinkedHashSet<URI> possibles = possibleLookups(serviceURI,
					usePathRecursion);

			Map<URI, URI> mappedServiceURIs = getFragmentMappedURIsForUsernamePassword();

			try {
				for (URI lookupURI : possibles) {
					URI mappedURI = mappedServiceURIs.get(lookupURI);
					if (mappedURI == null) {
						continue;
					}
					// Get it
					String alias = null;
					alias = "password#" + mappedURI.toASCIIString();
					passwordKey = (((SecretKeySpec) keystore
							.getKey(alias, masterPassword.toCharArray())));
					if (passwordKey == null) {
						// Unexpected, it was just there in the map!
						logger.warn("Could not find alias " + alias
								+ " for known uri " + lookupURI
								+ ", just deleted?");
						// Remember we went outside synchronized(keystore) while
						// looping
						continue;
					}
					String unpasspair = new String(passwordKey.getEncoded(),
							UTF_8);
					/*
					 * decoded key contains string
					 * <USERNAME><SEPARATOR_CHARACTER><PASSWORD>
					 */

					int separatorAt = unpasspair
							.indexOf(CredentialManager.USERNAME_AND_PASSWORD_SEPARATOR_CHARACTER);
					if (separatorAt < 0) {
						throw new CMException("Invalid credentials stored for "
								+ lookupURI);
					}
					String username = unpasspair.substring(0, separatorAt);
					String password = unpasspair.substring(separatorAt + 1);

					UsernamePassword usernamePassword = new UsernamePassword();
					usernamePassword.setUsername(username);
					usernamePassword.setPassword(password.toCharArray());
					return usernamePassword;
				}

				// Nothing found in the Keystore, let's lookup using SPIs
				for (CredentialProviderSPI credProvider : findMasterPasswordProviders()) {
					if (credProvider.canProvideUsernamePassword(serviceURI)) {
						UsernamePassword usernamePassword = credProvider
								.getUsernamePassword(serviceURI,
										requestingPrompt);
						if (usernamePassword == null) {
							continue;
						}
						if (usernamePassword.isShouldSave()) {
							URI uri = serviceURI;
							if (usePathRecursion) {
								uri = normalizeServiceURI(serviceURI);
							}
							saveUsernameAndPasswordForService(usernamePassword,
									uri);
						}
						return usernamePassword;
					}
				}
				// Giving up
				return null;
			} catch (Exception ex) {
				String exMessage = "Credential Manager: Failed to get the username and password pair for service "
						+ serviceURI + " from the Keystore.";
				logger.error(exMessage, ex);
				throw (new CMException(exMessage));
			}
		}
	}

	protected Map<URI, URI> getFragmentMappedURIsForUsernamePassword()
			throws CMException {
		synchronized (Security.class) {
			if (cachedServiceMap == null) {
				HashMap<URI, URI> map = new HashMap<URI, URI>();
				for (URI serviceURI : getServiceURIsForUsernamePassword()) {
					// Always store 1-1, with or without fragment
					map.put(serviceURI, serviceURI);
					if (serviceURI.getFragment() == null) {
						continue;
					}
					// Look up the no-fragment uri as an additional mapping
					URI noFragment;
					try {
						noFragment = setFragmentForURI(serviceURI, null);
					} catch (URISyntaxException e) {
						logger.warn("Could not reset fragment for service URI "
								+ serviceURI);
						continue;
					}
					if (map.containsKey(noFragment)) {
						if (map.get(noFragment).getFragment() != null) {
							// No mapping for duplicates
							map.remove(noFragment);
							continue;
						} // else it's noFragment -> noFragment, which is OK
					} else {
						// Brand new, put it in
						map.put(noFragment, serviceURI);
					}
				}
				cachedServiceMap = map;
			}
			return cachedServiceMap;
		}
	}

	protected static LinkedHashSet<URI> possibleLookups(URI serviceURI,
			boolean usePathRecursion) {
		serviceURI = serviceURI.normalize();
		try {
			serviceURI = setUserInfoForURI(serviceURI, null);
		} catch (URISyntaxException ex) {
			logger.warn("Could not strip userinfo from " + serviceURI, ex);
		}

		/*
		 * We'll use a LinkedHashSet to avoid checking for duplicates, like if
		 * serviceURI.equals(withoutQuery) Only the first hit should be added to
		 * the set.
		 */
		LinkedHashSet<URI> possibles = new LinkedHashSet<URI>();

		possibles.add(serviceURI);
		if (!usePathRecursion || !serviceURI.isAbsolute()) {
			return possibles;
		}

		/*
		 * We'll preserve the fragment, as it is used to indicate the realm
		 */
		String rawFragment = serviceURI.getRawFragment();
		if (rawFragment == null) {
			rawFragment = "";
		}
		URI withoutQuery = serviceURI.resolve(serviceURI.getRawPath());
		addFragmentedURI(possibles, withoutQuery, rawFragment);

		// Immediate parent
		URI parent = withoutQuery.resolve(".");
		addFragmentedURI(possibles, parent, rawFragment);
		URI oldParent = null;
		// Top parent (to be added later)
		URI root = parent.resolve("/");
		while (!parent.equals(oldParent) && !parent.equals(root)
				&& parent.getPath().length() > 0) {
			// Intermediate parents, but not for "http://bla.org" as we would
			// find "http://bla.org.."
			oldParent = parent;
			parent = parent.resolve("..");
			addFragmentedURI(possibles, parent, rawFragment);
		}
		// In case while-loop did not do so, also include root
		addFragmentedURI(possibles, root, rawFragment);
		if (rawFragment.length() > 0) {
			// Add the non-fragment versions in the bottom of the list
			for (URI withFragment : new ArrayList<URI>(possibles)) {
				try {
					possibles.add(setFragmentForURI(withFragment, null));
				} catch (URISyntaxException e) {
					logger.warn("Could not non-fragment URI " + withFragment);
				}
			}
		}
		return possibles;
	}

	private static void addFragmentedURI(LinkedHashSet<URI> possibles, URI uri,
			String rawFragment) {
		if (rawFragment != null && rawFragment.length() > 0) {
			uri = uri.resolve("#" + rawFragment);
		}
		possibles.add(uri);
	}

	/**
	 * Get service URLs associated with all username/password pairs currently in
	 * the Keystore.
	 * 
	 * @deprecated
	 * @see #getServiceURIsForUsernamePassword()
	 */
	@Deprecated
	public ArrayList<String> getServiceURLsforUsernameAndPasswords()
			throws CMException {
		List<URI> uris = getServiceURIsForUsernamePassword();
		ArrayList<String> serviceURLs = new ArrayList<String>();
		for (URI uri : uris) {
			serviceURLs.add(uri.toASCIIString());
		}
		return serviceURLs;
	}

	/**
	 * Insert a new username and password pair in the keystore for the given
	 * service URL.
	 * <p>
	 * Effectively, this method inserts a new secret key entry in the keystore,
	 * where key contains <USERNAME>"\000"<PASSWORD> string, i.e. password is
	 * prepended with the username and separated by a \000 character (which
	 * hopefully will not appear in the username).
	 * <p>
	 * Username and password string is saved in the Keystore as byte array using
	 * SecretKeySpec (which constructs a secret key from the given byte array
	 * but does not check if the given bytes indeed specify a secret key of the
	 * specified algorithm).
	 * <p>
	 * An alias used to identify the username and password entry is constructed
	 * as "password#"<SERVICE_URL> using the service URL this username/password
	 * pair is to be used for.
	 * <p>
	 * 
	 * @param usernamePassword
	 *            The {@link UsernamePassword} to store
	 * @param serviceURI
	 *            The (possibly normalized) URI to store the credentials under
	 * @throws CMException
	 *             If the credentials could not be stored
	 */
	public void saveUsernameAndPasswordForService(
			UsernamePassword usernamePassword, URI serviceURI)
			throws CMException {
		String uri = serviceURI.toASCIIString();
		saveUsernameAndPasswordForService(usernamePassword.getUsername(),
				String.valueOf(usernamePassword.getPassword()), uri);
	}

	/**
	 * Insert a new username and password pair in the Keystore for the given
	 * service URL.
	 * <p>
	 * Effectively, this method inserts a new secret key entry in the Keystore,
	 * where key contains <USERNAME>"\000"<PASSWORD> string, i.e. password is
	 * prepended with the username and separated by a \000 character.
	 * <p>
	 * Username and password string is saved in the Keystore as byte array using
	 * SecretKeySpec (which constructs a secret key from the given byte array
	 * but does not check if the given bytes indeed specify a secret key of the
	 * specified algorithm).
	 * <p>
	 * An alias used to identify the username and password entry is constructed
	 * as "password#"<SERVICE_URL> using the service URL this username/password
	 * pair is to be used for.
	 * <p>
	 * 
	 * @deprecated Use
	 *             {@link #saveUsernameAndPasswordForService(UsernamePassword, URI)}
	 *             instead
	 * @param username
	 *            Username to store
	 * @param password
	 *            Password to store
	 * @param serviceURL
	 *            serviceURI The (possibly normalized) URI to store the
	 *            credentials under
	 * @throws CMException
	 *             If the credentials could not be stored
	 */
	@Deprecated
	public void saveUsernameAndPasswordForService(String username,
			String password, String serviceURL) throws CMException {

		synchronized (keystore) {

			// Alias for the username and password entry
			String alias = "password#" + serviceURL;
			/*
			 * Password (together with its related username) is wrapped as a
			 * SecretKeySpec that implements SecretKey and constructs a secret
			 * key from the given password as a byte array. The reason for this
			 * is that we can only save instances of Key objects in the
			 * Keystore, and SecretKeySpec class is useful for raw secret keys
			 * (i.e. username and passwords concats) that can be represented as
			 * a byte array and have no key or algorithm parameters associated
			 * with them, e.g., DES or Triple DES. That is why we create it with
			 * the name "DUMMY" for algorithm name, as this is not checked for
			 * anyway.
			 * 
			 * Use a separator character that will not appear in the username or
			 * password.
			 */
			String keyToSave = username
					+ USERNAME_AND_PASSWORD_SEPARATOR_CHARACTER + password;

			SecretKeySpec passwordKey;
			try {
				passwordKey = new SecretKeySpec(keyToSave.getBytes(UTF_8),
						"DUMMY");
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException("Could not find encoding " + UTF_8);
			}
			try {
				keystore.setKeyEntry(alias, passwordKey, masterPassword.toCharArray(), null);
				saveKeystore(KEYSTORE);
				multiCaster.notify(new KeystoreChangedEvent(
						CredentialManager.KEYSTORE));
			} catch (Exception ex) {
				String exMessage = "Credential Manager: Failed to insert username and password pair for service "
						+ serviceURL + " in the Keystore.";
				logger.error(exMessage, ex);
				throw (new CMException(exMessage));
			}
		}
	}

	/**
	 * Delete a username and password pair for the given service URL from the
	 * Keystore.
	 */
	public void deleteUsernameAndPasswordForService(String serviceURL)
			throws CMException {
		synchronized (keystore) {
			deleteEntry(KEYSTORE, "password#" + serviceURL);
			saveKeystore(KEYSTORE);
			multiCaster.notify(new KeystoreChangedEvent(CredentialManager.KEYSTORE));	
		}
	}

	/**
	 * Get a key pair entry's private key for the given service URL.
	 */
	public PrivateKey getPrivateKey(String serviceURL) {
		// TODO
		return null;
	}

	/**
	 * Get a key pair entry's public key certificate chain for the given service
	 * URL.
	 */
	public Certificate[] getPublicKeyCertificateChain(String serviceURL) {
		// TODO
		return null;
	}

	/**
	 * Insert a new key entry containing private key and public key certificate
	 * (chain) in the Keystore and save the list of service URLs this key pair
	 * is associated to.
	 * 
	 * An alias used to identify the keypair entry is constructed as:
	 * "keypair#"<CERT_SUBJECT_COMMON_NAME>"#"<CERT_ISSUER_COMMON_NAME>"#"<
	 * CERT_SERIAL_NUMBER>
	 */
//	public void saveKeyPair(Key privateKey, Certificate[] certs,
//			ArrayList<String> serviceURLs) throws CMException {
//
//		synchronized (Security.class) {
//			ArrayList<Provider> oldBCProviders = unregisterOldBCProviders();
//			Security.addProvider(bcProvider);
//
//			// Create an alias for the new key pair entry in the Keystore
//			// as
//			// "keypair#"<CERT_SUBJECT_COMMON_NAME>"#"<CERT_ISSUER_COMMON_NAME>"#"<CERT_SERIAL_NUMBER>
//			String ownerDN = ((X509Certificate) certs[0])
//					.getSubjectX500Principal().getName(X500Principal.RFC2253);
//			CMX509Util util = new CMX509Util();
//			util.parseDN(ownerDN);
//			String ownerCN = util.getCN(); // owner's common name
//
//			// Get the hexadecimal representation of the certificate's serial
//			// number
//			String serialNumber = new BigInteger(1,
//					((X509Certificate) certs[0]).getSerialNumber()
//							.toByteArray()).toString(16).toUpperCase();
//
//			String issuerDN = ((X509Certificate) certs[0])
//					.getIssuerX500Principal().getName(X500Principal.RFC2253);
//			util.parseDN(issuerDN);
//			String issuerCN = util.getCN(); // issuer's common name
//
//			String alias = "keypair#" + ownerCN + "#" + issuerCN + "#"
//					+ serialNumber;
//
//			try {
//				keystore.setKeyEntry(alias, privateKey, null, certs);
//				saveKeystore(KEYSTORE);
//
//				// Add service url list to the serviceURLs hashmap
//				// (overwrites previous
//				// value, if any)
//				if (serviceURLs == null)
//					serviceURLs = new ArrayList<String>();
//				serviceURLsForKeyPairs.put(alias, serviceURLs);
//				// Save the updated hashmap to the file
//				saveServiceURLsForKeyPairs();
//				multiCaster.notify(new KeystoreChangedEvent(
//						CredentialManager.KEYSTORE));
//
//			} catch (Exception ex) {
//				String exMessage = "Credential Manager: Failed to insert the key pair entry in the Keystore.";
//				logger.error(exMessage, ex);
//				throw (new CMException(exMessage));
//			} finally {
//				// Add the old BC providers back and remove the one we have
//				// added
//				restoreOldBCProviders(oldBCProviders);
//			}
//		}
//	}

	/**
	 * Insert a new key entry containing private key and the corresponding 
	 * public key certificate chain in the Keystore.
	 * 
	 * An alias used to identify the keypair entry is constructed as:
	 * "keypair#"<CERT_SUBJECT_COMMON_NAME>"#"<CERT_ISSUER_COMMON_NAME>"#"<
	 * CERT_SERIAL_NUMBER>
	 */
	public void saveKeyPair(Key privateKey, Certificate[] certs) throws CMException {

		synchronized (keystore) {
			// Create an alias for the new key pair entry in the Keystore
			// as "keypair#"<CERT_SUBJECT_COMMON_NAME>"#"<CERT_ISSUER_COMMON_NAME>"#"<CERT_SERIAL_NUMBER>
			String ownerDN = ((X509Certificate) certs[0])
					.getSubjectX500Principal().getName(X500Principal.RFC2253);
			CMX509Util util = new CMX509Util();
			util.parseDN(ownerDN);
			String ownerCN = util.getCN(); // owner's common name

			// Get the hexadecimal representation of the certificate's serial
			// number
			String serialNumber = new BigInteger(1,
					((X509Certificate) certs[0]).getSerialNumber()
							.toByteArray()).toString(16).toUpperCase();

			String issuerDN = ((X509Certificate) certs[0])
					.getIssuerX500Principal().getName(X500Principal.RFC2253);
			util.parseDN(issuerDN);
			String issuerCN = util.getCN(); // issuer's common name

			String alias = "keypair#" + ownerCN + "#" + issuerCN + "#"
					+ serialNumber;

			try {
				keystore.setKeyEntry(alias, privateKey, masterPassword.toCharArray(), certs);
				saveKeystore(KEYSTORE);
				multiCaster.notify(new KeystoreChangedEvent(
						CredentialManager.KEYSTORE));
				// Set the new SSLSocketFactory to use the updated Truststore
				HttpsURLConnection.setDefaultSSLSocketFactory(createTavernaSSLSocketFactory());
				logger.info("Credential Manager: Updating SSLSocketFactory after inserting a key pair.");
			} catch (Exception ex) {
				String exMessage = "Credential Manager: Failed to insert the key pair entry in the Keystore.";
				//logger.error(exMessage, ex);
				throw (new CMException(exMessage, ex));
			}
		}
	}
	
	/**
	 * Checks if the Keystore contains the key pair entry.
	 */
	public boolean containsKeyPair(Key privateKey, Certificate[] certs)
			throws CMException {
		synchronized (keystore) {
			// Create an alias for the new key pair entry in the Keystore
			// as "keypair#"<CERT_SUBJECT_COMMON_NAME>"#"<CERT_ISSUER_COMMON_NAME>"#"<CERT_SERIAL_NUMBER>
			String ownerDN = ((X509Certificate) certs[0])
					.getSubjectX500Principal().getName(X500Principal.RFC2253);
			CMX509Util util = new CMX509Util();
			util.parseDN(ownerDN);
			String ownerCN = util.getCN(); // owner's common name

			// Get the hexadecimal representation of the certificate's serial
			// number
			String serialNumber = new BigInteger(1,
					((X509Certificate) certs[0]).getSerialNumber()
							.toByteArray()).toString(16).toUpperCase();

			String issuerDN = ((X509Certificate) certs[0])
					.getIssuerX500Principal().getName(X500Principal.RFC2253);
			util.parseDN(issuerDN);
			String issuerCN = util.getCN(); // issuer's common name

			String alias = "keypair#" + ownerCN + "#" + issuerCN + "#"
					+ serialNumber;

			try {
				return keystore.containsAlias(alias);
			} catch (KeyStoreException ex) {
				String exMessage = "Credential Manager: Failed to get aliases from the Keystore to check if it contains the given key pair.";
				logger.error(exMessage, ex);
				throw (new CMException(exMessage));
			}
		}
	}

	/**
	 * Deletes a key pair entry from the Keystore.
	 */
	public void deleteKeyPair(String alias) throws CMException {

		// TODO: We are passing alias for now but we want to be passing
		// the private key and its public key certificate.

		// // Create an alias for the new key pair entry in the Keystore
		// // as
		// "keypair#"<CERT_SUBJECT_COMMON_NAME>"#"<CERT_ISSUER_COMMON_NAME>"#"<CERT_SERIAL_NUMBER>
		// String ownerDN = ((X509Certificate)
		// certs[0]).getSubjectX500Principal()
		// .getName(X500Principal.RFC2253);
		// CMX509Util util = new CMX509Util();
		// util.parseDN(ownerDN);
		// String ownerCN = util.getCN(); // owner's common name
		//
		// // Get the hexadecimal representation of the certificate's serial
		// number
		// String serialNumber = new BigInteger(1, ((X509Certificate) certs[0])
		// .getSerialNumber().toByteArray()).toString(16)
		// .toUpperCase();
		//
		// String issuerDN = ((X509Certificate)
		// certs[0]).getIssuerX500Principal()
		// .getName(X500Principal.RFC2253);
		// util.parseDN(issuerDN);
		// String issuerCN = util.getCN(); // issuer's common name
		//		
		// String alias = "keypair#" + ownerCN + "#" + issuerCN + "#" +
		// serialNumber;
		synchronized (keystore) {
			deleteEntry(KEYSTORE, alias);
			saveKeystore(KEYSTORE);
			//deleteServiceURLsForKeyPair(alias);
			multiCaster.notify(new KeystoreChangedEvent(CredentialManager.KEYSTORE));
			// Set the new SSLSocketFactory to use the updated Keystore
			HttpsURLConnection.setDefaultSSLSocketFactory(createTavernaSSLSocketFactory());
			logger.info("Credential Manager: Updating SSLSocketFactory "
					+ "after deleting a keypair.");	
		}
	}

	/**
	 * Exports a key entry containing private key and public key certificate
	 * chain from the Keystore to a PKCS #12 file.
	 */
	public void exportKeyPair(String alias, File exportFile,
			String pkcs12Password) throws CMException {

		FileOutputStream fos = null;

		synchronized (keystore) {
			// Export the key pair
			try {

				// Get the private key for the alias
				PrivateKey privateKey = (PrivateKey) keystore.getKey(alias,
						masterPassword.toCharArray());

				// Get the related public key's certificate chain
				Certificate[] certChain = getCertificateChain(alias);

				// Create a new PKCS #12 keystore
				KeyStore newPkcs12 = KeyStore.getInstance("PKCS12", "BC");
				newPkcs12.load(null, null);

				// Place the private key and certificate chain into the PKCS #12
				// keystore.
				// Construct a new alias as
				// "<SUBJECT_COMMON_NAME>'s <ISSUER_ORGANISATION> ID"

				String sDN = ((X509Certificate) certChain[0])
						.getSubjectX500Principal().getName(
								X500Principal.RFC2253);
				CMX509Util util = new CMX509Util();
				util.parseDN(sDN);
				String sCN = util.getCN();

				String iDN = ((X509Certificate) certChain[0])
						.getIssuerX500Principal()
						.getName(X500Principal.RFC2253);
				util.parseDN(iDN);
				String iCN = util.getCN();

				String pkcs12Alias = sCN + "'s " + iCN + " ID";
				newPkcs12.setKeyEntry(pkcs12Alias, privateKey, new char[0],
						certChain);

				// Store the new PKCS #12 keystore on the disk
				fos = new FileOutputStream(exportFile);
				newPkcs12.store(fos, pkcs12Password.toCharArray());
				fos.close();
			} catch (Exception ex) {
				String exMessage = "Credential Manager: Failed to export the key pair from the Keystore.";
				logger.error(exMessage, ex);
				throw (new CMException(exMessage));
			} finally {
				if (fos != null) {
					try {
						fos.close();
					} catch (IOException e) {
						// ignore
					}
				}
			}
		}
	}

	/**
	 * Get certificate entry from the Keystore or Truststore. If the given alias
	 * name identifies a trusted certificate entry, the certificate associated
	 * with that entry is returned from the Truststore. If the given alias name
	 * identifies a key pair entry, the first element of the certificate chain
	 * of that entry is returned from the Keystore.
	 */
	public Certificate getCertificate(String ksType, String alias)
			throws CMException {
		try {
			if (ksType.equals(KEYSTORE)) {
				synchronized (keystore) {
					return keystore.getCertificate(alias);					
				}
			} else if (ksType.equals(TRUSTSTORE)) {
				synchronized (truststore) {
					return truststore.getCertificate(alias);					
				}
			} else {
				return null;
			}
		} catch (Exception ex) {
			String exMessage = "Credential Manager: Failed to fetch certificate from the "
					+ ksType + ".";
			logger.error(exMessage, ex);
			throw (new CMException(exMessage));
		}
	}

	/**
	 * Gets certificate chain for the key pair entry from the Keystore. This
	 * method works for Keystore only as Truststore does not contain key pair
	 * entries, but trusted certificate entries only.
	 */
	public Certificate[] getCertificateChain(String alias) throws CMException {
		synchronized (keystore) {
			try {
				return keystore.getCertificateChain(alias);
			} catch (Exception ex) {
				String exMessage = "Credential Manager: Failed to fetch certificate chain for the keypair from the Keystore";
				logger.error(exMessage, ex);
				throw (new CMException(exMessage));
			}
		}
	}

	/**
	 * Inserts a trusted certificate entry in the Truststore with an alias
	 * constructed as:
	 * 
	 * "trustedcert#<CERT_SUBJECT_COMMON_NAME>"#"<CERT_ISSUER_COMMON_NAME>"#
	 * "<CERT_SERIAL_NUMBER>
	 */
	public void saveTrustedCertificate(X509Certificate cert) throws CMException {

		synchronized (truststore) {
			// Create an alias for the new trusted certificate entry in the Truststore
			// as "trustedcert#"<CERT_SUBJECT_COMMON_NAME>"#"<CERT_ISSUER_COMMON_NAME>"#"<CERT_SERIAL_NUMBER>
			String alias = createX509CertificateAlias(cert);
			try {
				truststore.setCertificateEntry(alias, cert);
				saveKeystore(TRUSTSTORE);
				multiCaster.notify(new KeystoreChangedEvent(
						CredentialManager.TRUSTSTORE));
				// Set the new SSLSocketFactory to use the updated Truststore
				HttpsURLConnection.setDefaultSSLSocketFactory(createTavernaSSLSocketFactory());
				logger.info("Credential Manager: Updating SSLSocketFactory after inserting a trusted certificate.");
			}
			catch (Exception ex) {
				String exMessage = "Credential Manager: Failed to insert trusted certificate entry in the Truststore.";
				//logger.error(exMessage, ex);
				throw (new CMException(exMessage, ex));
			}
		}
	}

	/**
	 * Create a Truststore alias for the trusted certificate as
	 * "trustedcert#"<CERT_SUBJECT_COMMON_NAME>"#"<CERT_ISSUER_COMMON_NAME>"#"<
	 * CERT_SERIAL_NUMBER>
	 */
	private static String createX509CertificateAlias(X509Certificate cert) {
		String ownerDN = cert.getSubjectX500Principal().getName(
				X500Principal.RFC2253);
		CMX509Util util = new CMX509Util();
		util.parseDN(ownerDN);
		String owner;
		String ownerCN = util.getCN(); // owner's common name
		String ownerOU = util.getOU();
		String ownerO = util.getO();
		if (!ownerCN.equals("none")) { // try owner's CN first
			owner = ownerCN;
		} // try owner's OU
		else if (!ownerOU.equals("none")) {
			owner = ownerOU;
		} else if (!ownerO.equals("none")) { // finally use owner's Organisation
			owner = ownerO;
		} else {
			owner = "<Not Part of Certificate>";
		}

		// Get the hexadecimal representation of the certificate's serial number
		String serialNumber = new BigInteger(1, cert.getSerialNumber()
				.toByteArray()).toString(16).toUpperCase();

		String issuerDN = cert.getIssuerX500Principal().getName(
				X500Principal.RFC2253);
		util.parseDN(issuerDN);
		String issuer;
		String issuerCN = util.getCN(); // issuer's common name
		String issuerOU = util.getOU();
		String issuerO = util.getO();
		if (!issuerCN.equals("none")) { // try issuer's CN first
			issuer = issuerCN;
		} // try issuer's OU
		else if (!issuerOU.equals("none")) {
			issuer = issuerOU;
		} else if (!issuerO.equals("none")) { // finally use issuer's
			// Organisation
			issuer = issuerO;
		} else {
			issuer = "<Not Part of Certificate>";
		}

		String alias = "trustedcert#" + owner + "#" + issuer + "#"
				+ serialNumber;
		return alias;
	}

	/**
	 * Deletes a trusted certificate entry from the Truststore.
	 */
	public void deleteTrustedCertificate(String alias) throws CMException {

		synchronized (truststore) {
			deleteEntry(TRUSTSTORE, alias);
			saveKeystore(TRUSTSTORE);
			multiCaster.notify(new KeystoreChangedEvent(
					CredentialManager.TRUSTSTORE));
			// Set the new SSLSocketFactory to use the updated Truststore
			HttpsURLConnection.setDefaultSSLSocketFactory(createTavernaSSLSocketFactory());
			logger.info("Credential Manager: Updating SSLSocketFactory "
					+ "after deleting a trusted certificate.");	
		}
	}

	/**
	 * Checks if the given entry is a key entry in the Keystore.
	 */
	public boolean isKeyEntry(String alias) throws CMException {

		try {
			synchronized (keystore) {
				return keystore.isKeyEntry(alias);
			}
		} catch (Exception ex) {
			String exMessage = "Credential Manager: Failed to access the key entry in the Keystore.";
			logger.error(exMessage, ex);
			throw (new CMException(exMessage));
		}
	}

	/**
	 * Deletes an entry from the Keystore or the Truststore.
	 */
	private void deleteEntry(String ksType, String alias) throws CMException {
		try {
			if (ksType.equals(KEYSTORE)) {
				synchronized (keystore) {
					keystore.deleteEntry(alias);
					// If this was key pair rather than password entry - remove
					// the associated URLs from the serviceURLsFile as well
//					if (alias.startsWith("keypair#"))
//						deleteServiceURLsForKeyPair(alias);	
				}
			} 
			else if (ksType.equals(TRUSTSTORE)) {
				synchronized (truststore) {
					truststore.deleteEntry(alias);					
				}
			}
		} catch (Exception ex) {
			String exMessage = "Credential Manager: Failed to delete the entry with alias "
					+ alias + "from the " + ksType + ".";
			logger.error(exMessage, ex);
			throw (new CMException(exMessage));
		}
	}

	/**
	 * Check if a keystore contains an entry with the given alias.
	 */
	public boolean containsAlias(String ksType, String alias)
			throws CMException {
			try {
				if (ksType.equals(KEYSTORE))
					synchronized (keystore) {						
						return keystore.containsAlias(alias);
					}
				else if (ksType.equals(TRUSTSTORE))
					synchronized (truststore) {
						return truststore.containsAlias(alias);						
					}
				else {
					return false;
				}
			} catch (Exception ex) {
				String exMessage = "Credential Manager: Failed to access the "
						+ ksType + " to check if an alias exists.";
				logger.error(exMessage, ex);
				throw (new CMException(exMessage));
			}
	}

	/**
	 * Gets all the aliases from the Keystore/Truststore or null if there was some error
	 * while accessing it.
	 */
	public ArrayList<String> getAliases(String ksType) throws CMException {

		try {
			if (ksType.equals(KEYSTORE)) {
				synchronized (keystore) {
					return Collections.list(keystore.aliases());					
				}
			} else if (ksType.equals(TRUSTSTORE)) {
				synchronized (truststore) {
					return Collections.list(truststore.aliases());					
				}
			} else {
				return null;
			}
		} catch (Exception ex) {
			String exMessage = "Credential Manager: Failed to access the "
					+ ksType + " to get the aliases.";
			logger.error(exMessage, ex);
			throw new CMException(exMessage);
		}
	}

	/**
	 * Get service URLs associated with all username/password pairs currently in
	 * the Keystore.
	 * 
	 * @see #hasUsernamePasswordForService(URI)
	 */
	public List<URI> getServiceURIsForUsernamePassword() throws CMException {
		synchronized (keystore) {
			if (cachedServiceURIs == null) {
				List<URI> serviceURIs = new ArrayList<URI>();
				for (String alias : getAliases(CredentialManager.KEYSTORE)) {
					/*
					 * We are only interested in username/password entries here.
					 * Alias for such entries is constructed as
					 * "password#"<SERVICE_URI> where SERVICE_URI is the service
					 * this username/password pair is to be used for.
					 */
					if (!alias.startsWith("password#")) {
						continue;
					}
					String[] split = alias.split("#", 2);
					if (split.length != 2) {
						logger.warn("Invalid alias " + alias);
						continue;
					}
					String uriStr = split[1];
					URI uri = URI.create(uriStr);
					serviceURIs.add(uri);
				}
				cachedServiceURIs = serviceURIs;
			}
			return cachedServiceURIs;
		}
	}

	/**
	 * Gets the creation date of an entry in the specified keystore.
	 * 
	 * Note that not all keystores support 'creation date' property, but Bouncy
	 * Castle 'UBER'-type keystores do.
	 */
//	public Date getEntryCreationDate(String ksType, String alias)
//			throws CMException {
//
//		synchronized (Security.class) {
//			ArrayList<Provider> oldBCProviders = unregisterOldBCProviders();
//			Security.addProvider(bcProvider);
//			try {
//				if (ksType.equals(KEYSTORE)) {
//					return keystore.getCreationDate(alias);
//				} else if (ksType.equals(TRUSTSTORE)) {
//					return truststore.getCreationDate(alias);
//				} else {
//					return null;
//				}
//			} catch (Exception ex) {
//				String exMessage = "Credential Manager: Failed to get the creation date for the entry from the "
//						+ ksType + ".";
//				logger.error(exMessage);
//				throw new CMException(exMessage);
//			} finally {
//				// Add the old BC providers back and remove the one we have
//				// added
//				restoreOldBCProviders(oldBCProviders);
//			}
//		}
//	}

	/**
	 * Check if Keystore/Truststore file already exists on disk.
	 */
	public boolean exists(String ksType) {

		if (ksType.equals(KEYSTORE))
			return keystoreFile.exists();
		else if (ksType.equals(TRUSTSTORE)) {
			return truststoreFile.exists();
		} else
			return false;
	}

	/**
	 * Save the Keystore back to the file it was originally loaded from.
	 */
	public void saveKeystore(String ksType) throws CMException {

		FileOutputStream fos = null;
		try {
			if (ksType.equals(KEYSTORE)) {
				synchronized (keystore) {
					fos = new FileOutputStream(keystoreFile);
					keystore.store(fos, masterPassword.toCharArray());
				}

			} else if (ksType.equals(TRUSTSTORE)) {
				synchronized (truststore) {
					fos = new FileOutputStream(truststoreFile);
					// Hard-coded trust store password
					truststore.store(fos, masterPassword.toCharArray());	
				}
			}
		} catch (Exception ex) {
			String exMessage = "Credential Manager: Failed to save the "
					+ ksType + ".";
			logger.error(exMessage, ex);
			throw (new CMException(exMessage));
		} finally {
			if (fos != null) {
				try {
					fos.close();
				} catch (IOException e) {
					// ignore
				}
			}
		}
	}

	/**
	 * Loads a PKCS12 keystore from the given file using the supplied password.
	 */
	public KeyStore loadPKCS12Keystore(File importFile, String pkcs12Password)
			throws CMException {

		// Load the PKCS #12 keystore from the file
		KeyStore pkcs12;
		try {
			pkcs12 = KeyStore.getInstance("PKCS12", "BC");
			pkcs12.load(new FileInputStream(importFile), pkcs12Password
					.toCharArray());
			return pkcs12;
		} catch (Exception ex) {
			String exMessage = "Credential Manager: Failed to open a PKCS12-type keystore.";
			logger.error(exMessage, ex);
			throw (new CMException(exMessage));
		}
	}

	public void addObserver(Observer<KeystoreChangedEvent> observer) {
		multiCaster.addObserver(observer);
	}

	public List<Observer<KeystoreChangedEvent>> getObservers() {
		return multiCaster.getObservers();
	}

	public void removeObserver(Observer<KeystoreChangedEvent> observer) {
		multiCaster.removeObserver(observer);
	}

	/**
	 * Checks if Credential Manager has been initialised.
	 */
	public static synchronized boolean isInitialised() {
		return (INSTANCE != null);
	}

	/**
	 * Checks if Keystore's master password is the same as the one provided.
	 */
	public static boolean confirmMasterPassword(String password) {
		return ((masterPassword != null) && masterPassword.equals(password));
	}

	/**
	 * Changes the Keystore master password. Truststore is using a different
	 * pre-set password.
	 */
	public void changeMasterPassword(String newPassword) throws CMException {
		masterPassword = newPassword;
		saveKeystore(KEYSTORE);
		saveKeystore(TRUSTSTORE);
	}

	/**
	 * Configures SSL properties (keystore and trustore) on a special SSL socket factory 
	 * to be used for HTTPS connections from Taverna.
	 * It has to initialize the Credential Manager (Keystore and Truststore) 
	 * in the process (from the default location).   
	 * @throws CMException
	 */
	public static void initialiseSSL() throws CMException {
		if (!sslInitialised) {
			getInstance();		
			// Set the SSL socket factory with the just loaded Keystore and Truststore
			HttpsURLConnection.setDefaultSSLSocketFactory(createTavernaSSLSocketFactory());
			sslInitialised = true;					
		}
	}
	
	/**
	 * Configures SSL properties (keystore and trustore) on a special SSL socket factory 
	 * to be used for HTTPS connections from Taverna.
	 * It has to initialize the Credential Manager (Keystore and Truststore) 
	 * in the process (from the default location) and using the given master password.   
	 * @throws CMException
	 */
	public static void initialiseSSL(String password) throws CMException {
		if (!sslInitialised) {
			getInstance(password);		
			// Set the SSL socket factory with the just loaded Keystore and Truststore
			HttpsURLConnection.setDefaultSSLSocketFactory(createTavernaSSLSocketFactory());
			sslInitialised = true;					
		}
	}
	
	/**
	 * Configures SSLContext (keystore and trustore) and a special SSLSocketFactory 
	 * to be used for HTTPS connections from Taverna.
	 * It has to initialize the Credential Manager (Keystore and Truststore) 
	 * in the process (from the files in the given directory) and using the given master password. 
	 * @throws CMException
	 */
	public static void initialiseSSL(String credentialManagerDirPath, String masterPassword) throws CMException {
		if (!sslInitialised) {
			getInstance(credentialManagerDirPath, masterPassword); // this will init the Keystore/Truststore/SSL system properties					
			// Set the SSL socket factory with the just loaded Keystore and Truststore
			HttpsURLConnection.setDefaultSSLSocketFactory(createTavernaSSLSocketFactory());
			sslInitialised = true;	
		}
	}
	
	/**
	 * Customised X509TrustManager that uses Credential Manager's Truststore for
	 * trust management. If HTTPS connection to an untrusted service is
	 * attempted it will also pop up a dialog asking the user to confirm if they
	 * want to trust it.
	 * 
	 */
	public static class MyX509TrustManager implements X509TrustManager {

		/*
		 * The default X509TrustManager returned by SunX509 provider. We'll delegate
		 * decisions to it, and fall back to ask the user if the
		 * default X509TrustManager doesn't trust it.
		 */
		X509TrustManager sunJSSEX509TrustManager;

		MyX509TrustManager() throws Exception {
			// Create a "default" JSSE X509TrustManager.
			TrustManagerFactory tmf = TrustManagerFactory.getInstance(
					"SunX509", "SunJSSE");

			synchronized (truststore) {
				tmf.init(truststore);
			}

			TrustManager tms[] = tmf.getTrustManagers();

			/*
			 * Iterate over the returned TrustManagers, look for an instance of
			 * X509TrustManager. If found, use that as our "default" trust
			 * manager.
			 */
			for (int i = 0; i < tms.length; i++) {
				if (tms[i] instanceof X509TrustManager) {
					sunJSSEX509TrustManager = (X509TrustManager) tms[i];
					return;
				}
			}

			// X509TrustManager not found - we have to fail the constructor.
			throw new Exception("Could not initialize Taverna's TrustManager.");
		}

		/*
		 * This method is called on the server-side for establishing trust with a client.
		 */
		public void checkClientTrusted(X509Certificate[] chain, String authType)
				throws CertificateException {
		}

		/*
		 * This method is called on the client-side for establishing trust with a server. 
		 * We first try to delegate to the default trust manager that uses Taverna's Truststore.
		 * If that falls through we ask the user if they want to trust the certificate. 
		 */
		public void checkServerTrusted(X509Certificate[] chain, String authType)
				throws CertificateException {
			try {
				sunJSSEX509TrustManager.checkServerTrusted(chain, authType);
			} catch (CertificateException excep) {
				// Pop up a dialog box asking whether to trust the server's
				// certificate chain.
				if (!shouldTrust(chain)) {
					throw excep;
				}
			}
		}

		/*
		 * Merely pass this through.
		 */
		public X509Certificate[] getAcceptedIssuers() {
			return sunJSSEX509TrustManager.getAcceptedIssuers();
		}
	}

	/**
	 * Checks if a service is trusted and if not - asks user if they want to
	 * trust it.
	 */
	public static boolean shouldTrust(final X509Certificate[] chain) throws IllegalArgumentException{
		if (chain == null || chain.length == 0) {
			throw new IllegalArgumentException(
					"At least one certificate needed in chain");
		}
		
		// If the certificate already exists in the truststore, it is implicitly trusted
		// This will try to avoid prompting user twice as checkServerTrusted() method gets called twice.
		// Well, this is not working - checkServerTrusted() is still called twice.
		String alias = createX509CertificateAlias(chain[0]);
		try {
			if (truststore.containsAlias(alias)) {
				return true;
			}
		} catch (KeyStoreException e) {
			// Ignore
		}
		
		String name = chain[0].getSubjectX500Principal().getName();
		for (CredentialProviderSPI confirm : findMasterPasswordProviders()) {
			if (!confirm.canHandleTrustConfirmation(chain)) {
				continue;
			}
			TrustConfirmation confirmation = confirm.shouldTrust(chain);
			if (confirmation == null) {
				// SPI can't say yes or no, try next one
				continue;
			}
			if (confirmation.isShouldTrust() && confirmation.isShouldSave()) {
				try {
					CredentialManager credManager = CredentialManager
							.getInstance();
					credManager
							.saveTrustedCertificate((X509Certificate) chain[0]);
					logger.info("Stored trusted certificate " + name);
				} catch (CMException ex) {
					logger.error("Credential Manager failed to "
							+ "save trusted certificate " + name, ex);
				}
			}
			if (logger.isDebugEnabled()) {
				if (confirmation.isShouldTrust()) {
					logger.debug("Trusting " + name + " according to "
							+ confirm);
				} else {
					logger.debug("Not trusting " + name + " according to "
							+ confirm);
				}
			}
			return confirmation.isShouldTrust();
		}
		logger
				.warn("No ConfirmTrustedCertificateSPI instances could could confirm or deny trusting of "
						+ name);
		// None of the SPIs (if any) could confirm
		return false;
	}

	/**
	 * SSL Socket factory used by Taverna that uses special
	 * {@link MyX509TrustManager} that gets initialised every time Credential
	 * Manager's Keystore or Truststore is updated.
	 * 
	 * Inspired by Tom Oinn's ThreadLocalSSLSoketFactory.
	 */
	public static SSLSocketFactory createTavernaSSLSocketFactory() throws CMException{

		SSLContext sc = null;
		try {
			sc = SSLContext.getInstance("SSLv3");
		} catch (NoSuchAlgorithmException e1) {
			throw new CMException(
					"Failed to create SSL socket factory: the SSL algorithm was not available from any crypto provider.",
					e1);
		}

		KeyManager[] keyManagers = null;
		synchronized (keystore) {
			try {
				// Create KeyManager factory and load Taverna's Keystore object
				// in it
				KeyManagerFactory keyManagerFactory = KeyManagerFactory
						.getInstance("SunX509", "SunJSSE");
				keyManagerFactory.init(keystore, masterPassword.toCharArray());
				keyManagers = keyManagerFactory.getKeyManagers();
			} catch (Exception e) {
				throw new CMException(
						"Failed to create SSL socket factory: could not initiate SSL key manager",
						e);
			}
		}

		TrustManager[] trustManagers = null;
		synchronized (truststore) {
			try {
				// Create our own TrustManager with Taverna's Truststore
				trustManagers = new TrustManager[] { new MyX509TrustManager() };
			} catch (Exception e) {
				throw new CMException(
						"Failed to create SSL socket factory: could not initiate SSL trust manager",
						e);
			}
		}

		try {
			sc.init(keyManagers, trustManagers, new SecureRandom());
		} catch (KeyManagementException kmex) {
			throw new CMException("Failed to initiate the SSL socet factory",
					kmex);
		}
		// Set the default SSLContext to be used for subsequent SSL sockets from Java
		SSLContext.setDefault(sc); 
		// Create SSL socket to be used for HTTPS connections from 
		// e.g. REST activity that uses Apache HTTP client library
		return sc.getSocketFactory();
	}

	/**
	 * Normalize an URI for insertion as the basis for path-recursive lookups,
	 * ie. strip query and filename. For example: <code>
	 * URI uri = URI.create("http://foo.org/dir1/dirX/../dir2/filename.html?q=x")
	 * System.out.println(CredentialManager.normalizeServiceURI(uri));
	 * >>> http://foo.org/dir1/dir2/
	 * uri = URI.create("http://foo.org/dir1/dir2/");
	 * System.out.println(CredentialManager.normalizeServiceURI(uri));
	 * >>> http://foo.org/dir1/dir2/
	 * </code>
	 * <p>
	 * Note that #fragments are preserved, as these are used to indicate HTTP
	 * Basic Auth realms
	 * 
	 * @param serviceURI
	 *            URI for a service that is to be normalized
	 * @return A normalized URI without query, userinfo or filename, ie. where
	 *         uri.resolve(".").equals(uri).
	 */
	public static URI normalizeServiceURI(URI serviceURI) {
		try {
			URI noUserInfo = setUserInfoForURI(serviceURI, null);
			URI normalized = noUserInfo.normalize();
			URI parent = normalized.resolve(".");
			// Strip userinfo, keep fragment
			URI withFragment = setFragmentForURI(parent, serviceURI
					.getFragment());
			return withFragment;
		} catch (URISyntaxException ex) {
			return serviceURI;
		}
	}

	public static URI setFragmentForURI(URI uri, String fragment)
			throws URISyntaxException {
		return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri
				.getPort(), uri.getPath(), uri.getQuery(), fragment);
	}

	public static URI setUserInfoForURI(URI uri, String userinfo)
			throws URISyntaxException {
		return new URI(uri.getScheme(), userinfo, uri.getHost(), uri.getPort(),
				uri.getPath(), uri.getQuery(), uri.getFragment());
	}

	/**
	 * Reset the VMs cache for authentication like HTTP Basic Auth.
	 * <p>
	 * Note that this method uses undocumented calls to
	 * <code>sun.net.www.protocol.http.AuthCacheValue</code> which might not be
	 * valid in virtual machines other than Sun Java 6. If these calls fail,
	 * this method will log the error and return <code>false</code>.
	 * 
	 * @return <code>true</code> if the VMs cache could be reset, or
	 *         <code>false</code> otherwise.
	 */
	public boolean resetAuthCache() {

		// Sun should expose an official API to do this
		try {
			Class<?> AuthCacheValue = Class
					.forName("sun.net.www.protocol.http.AuthCacheValue");
			Class<?> AuthCacheImpl = Class
					.forName("sun.net.www.protocol.http.AuthCacheImpl");
			Class<?> AuthCache = Class
					.forName("sun.net.www.protocol.http.AuthCache");
			Method setAuthCache = AuthCacheValue.getMethod("setAuthCache",
					AuthCache);
			setAuthCache.invoke(null, AuthCacheImpl.newInstance());
			return true;
		} catch (Exception ex) {
			logger
					.warn(
							"Could not reset authcache, non-Sun VM or internal Sun classes changed",
							ex);
			return false;
		}
	}

	public boolean hasUsernamePasswordForService(URI uri) throws CMException {
		Map<URI, URI> mappedServiceURIs = getFragmentMappedURIsForUsernamePassword();
		for (URI possible : possibleLookups(uri, true)) {
			if (mappedServiceURIs.containsKey(possible)) {
				return true;
			}
		}
		return false;
	}
	
	private static void loadDefaultConfigurationFiles() {
		if (credentialManagerDirectory == null){
			credentialManagerDirectory = CMUtil.getCredentialManagerDefaultDirectory();
		}
		if (keystoreFile == null){
			keystoreFile = new File(credentialManagerDirectory, T2KEYSTORE_FILE);
		}
		if (truststoreFile == null){
			truststoreFile = new File(credentialManagerDirectory,T2TRUSTSTORE_FILE);
		}
//		if (serviceURLsFile == null){
//			serviceURLsFile = new File(credentialManagerDirectory,SERVICE_URLS_FILE);
//		}
	}

	private void loadConfigurationFiles(String credentialManagerDirPath)
			throws CMException {
		
		if (credentialManagerDirectory == null) {
			try {
				credentialManagerDirectory = new File(credentialManagerDirPath);
			} catch (Exception e) {
				throw new CMException(
						"Failed to open Credential Manager's directory to load the security files: " + e.getMessage(),
						e);
			}
		}
		if (keystoreFile == null){
			keystoreFile = new File(credentialManagerDirectory, T2KEYSTORE_FILE);
		}
		if (truststoreFile == null){
			truststoreFile = new File(credentialManagerDirectory,T2TRUSTSTORE_FILE);
		}
//		if (serviceURLsFile == null){
//			serviceURLsFile = new File(credentialManagerDirectory,SERVICE_URLS_FILE);
//		}
	}
	
	/**
	 * Load lists of service URLs associated with private key aliases from a
	 * file and populate the serviceURLs hashmap.
	 */
//	public void loadServiceURLsForKeyPairs() throws CMException {
//
//		serviceURLsForKeyPairs = new HashMap<String, ArrayList<String>>();
//
//		synchronized (Security.class) {
//			ArrayList<Provider> oldBCProviders = unregisterOldBCProviders();
//			Security.addProvider(bcProvider);
//
//			try {
//				// Create an empty map with aliases as keys
//				for (Enumeration<String> e = keystore.aliases(); e
//						.hasMoreElements();) {
//					String element = (String) e.nextElement();
//					/*
//					 * We want only key pair entry aliases (and not password
//					 * entry aliases)
//					 */
//					if (element.startsWith("keypair#"))
//						serviceURLsForKeyPairs.put(element,
//								new ArrayList<String>());
//				}
//			} catch (Exception ex) {
//				String exMessage = "Credential Manager: Failed to get private key aliases when loading service URLs.";
//				logger.error(exMessage, ex);
//				throw (new CMException(exMessage));
//			}
//
//			/*
//			 * If Service URLs file exists - load the URL lists from the file
//			 */
//			if (serviceURLsFile.exists()) {
//				BufferedReader serviceURLsReader = null;
//
//				try {
//					serviceURLsReader = new BufferedReader(new FileReader(
//							serviceURLsFile));
//
//					String line = serviceURLsReader.readLine();
//					while (line != null) {
//
//						/*
//						 * Line consists of an URL-encoded URL and alias
//						 * separated by a blank character, i.e.
//						 * line=<ENCODED_URL>" "<ALIAS> One alias can have more
//						 * than one URL associated with it (i.e. more than one
//						 * line in the file can exist for the same alias).
//						 */
//						String alias = line.substring(line.indexOf(' ') + 1);
//						String url = line.substring(0, line.indexOf(' '));
//						// URLs were encoded before storing them in a file
//						url = URLDecoder.decode(url, UTF_8);
//
//						ArrayList<String> urlsList = (ArrayList<String>) serviceURLsForKeyPairs
//								.get(alias); // get URL list for the current
//						// alias (it can be empty)
//						if (urlsList == null) {
//							urlsList = new ArrayList<String>();
//						}
//						urlsList.add(url); // add the new URL to the list of
//						// URLs for this alias
//						serviceURLsForKeyPairs.put(alias, urlsList); // put the updated list back to the map
//						line = serviceURLsReader.readLine();
//					}
//				} catch (Exception ex) {
//					String exMessage = "Credential Manager: Failed to read the service URLs file.";
//					logger.error(exMessage, ex);
//					throw (new CMException(exMessage));
//				} finally {
//					if (serviceURLsReader != null) {
//						try {
//							serviceURLsReader.close();
//						} catch (IOException e) {
//							// ignore
//						}
//					}
//
//					/*
//					 * Add the old BC providers back and remove the one we have
//					 * added
//					 */
//					restoreOldBCProviders(oldBCProviders);
//				}
//			}
//		}
//	}

	/**
	 * Add the service URLs list associated with a private key entry to the
	 * serviceURLs hashmap and save/update the service URLs file.
	 */
//	public void saveServiceURLsForKeyPair(String alias,
//			ArrayList<String> serviceURLsList) throws CMException {
//
//		/*
//		 * Add service url list to the serviceURLs hashmap (overwrites previous
//		 * value, if any
//		 */
//
//		serviceURLsForKeyPairs.put(alias, serviceURLsList);
//
//		// Save the updated hashmap to the file
//		saveServiceURLsForKeyPairs();
//	}

	/**
	 * Get the service URLs list associated with a private key entry.
	 */
//	public ArrayList<String> getServiceURLsForKeyPair(String alias) {
//		return serviceURLsForKeyPairs.get(alias);
//	}

	/**
	 * Get a map of service URL lists for each of the private key entries in the
	 * Keystore.
	 */
//	public HashMap<String, ArrayList<String>> getServiceURLsforKeyPairs() {
//		return serviceURLsForKeyPairs;
//	}

	/**
	 * Delete the service URLs list associated with a private key entry.
	 */
//	public void deleteServiceURLsForKeyPair(String alias) throws CMException {
//		// Remove service URL list from the serviceURLs hashmap
//		serviceURLsForKeyPairs.remove(alias);
//
//		// Save the updated serviceURLs hashmap to the file
//		saveServiceURLsForKeyPairs();
//	}

	/**
	 * Save the content of serviceURLs map to a file. Overwrites previous
	 * content of the file.
	 */
//	public void saveServiceURLsForKeyPairs() throws CMException {
//		synchronized (Security.class) {
//			// If file already exists
//			if (serviceURLsFile.exists()) {
//				// Delete the previous contents of the file
//				serviceURLsFile.delete();
//			}
//
//			// Create a new empty file
//			try {
//				serviceURLsFile.createNewFile();
//			} catch (IOException ex) {
//				String exMessage = "Credential Manager: Failed to create a new service URLs' file.";
//				logger.error(exMessage, ex);
//				throw (new CMException(exMessage));
//			}
//
//			BufferedWriter serviceURLsWriter = null;
//
//			try {
//
//				// Open the file for writing
//				serviceURLsWriter = new BufferedWriter((new FileWriter(
//						serviceURLsFile, false)));
//
//				// Write the serviceURLs hashmap to the file
//				for (String alias : serviceURLsForKeyPairs.keySet()) {
//					/*
//					 * for all aliases
//					 */
//
//					/*
//					 * For all urls associated with the alias
//					 */
//					ArrayList<String> serviceURLsForKeyPair = (ArrayList<String>) serviceURLsForKeyPairs
//							.get(alias);
//					for (String serviceURL : serviceURLsForKeyPair) {
//						/*
//						 * Each line of the file contains an encoded service URL
//						 * with its associated alias appended and separated from
//						 * the URL by a blank character ' ', i.e.
//						 * line=<ENCODED_URL>" "<ALIAS>
//						 */
//						/*
//						 * Service URLs are encoded before saving to make sure
//						 * they do not contain blank characters that are used as
//						 * delimiters.
//						 */
//						String encodedURL = URLEncoder.encode(
//								(String) serviceURL, UTF_8);
//						StringBuffer line = new StringBuffer(encodedURL + " "
//								+ alias);
//						serviceURLsWriter.append(line);
//						serviceURLsWriter.newLine();
//					}
//				}
//			} catch (FileNotFoundException ex) {
//				// Should not happen
//			} catch (IOException ex) {
//				String exMessage = "Credential Manager: Failed to save the service URLs to the file.";
//				logger.error(exMessage, ex);
//				throw (new CMException(exMessage));
//			} finally {
//				if (serviceURLsWriter != null) {
//					try {
//						serviceURLsWriter.close();
//					} catch (IOException e) {
//						// ignore
//					}
//				}
//			}
//		}
//	}

//	private static void restoreOldBCProviders(ArrayList<Provider> oldBCProviders) {
//	Security.removeProvider("BC");
//	for (Provider prov : oldBCProviders) {
//		Security.addProvider(prov);
//	}
//}
//
//private static ArrayList<Provider> unregisterOldBCProviders() {
//	ArrayList<Provider> oldBCProviders = new ArrayList<Provider>();
//	/*
//	 * Different versions of Bouncy Castle provider may be lurking around.
//	 * E.g. an old 1.25 version of Bouncy Castle provider is added by caGrid
//	 * package and others may be as well by third party providers
//	 */
//	for (int i = 0; i < Security.getProviders().length; i++) {
//		if (Security.getProviders()[i].getName().equals("BC")) {
//			oldBCProviders.add(Security.getProviders()[i]);
//		}
//	}
//	// Remove (hopefully) all registered BC providers
//	Security.removeProvider("BC");
//	return oldBCProviders;
//}

}