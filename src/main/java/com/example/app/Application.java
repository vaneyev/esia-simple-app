package com.example.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.CMSTypedData;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.util.Store;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootApplication
@RestController
public class Application {
	private String cerPath = "D:\\data\\projects\\root.pem";
	private String keyPath = "D:\\data\\projects\\priv.pem";
	private String authurl = "https://esia-portal1.test.gosuslugi.ru/aas/oauth2/ac?";
	private String tokenurl = "https://esia-portal1.test.gosuslugi.ru/aas/oauth2/te";
	private String resturl = "https://esia-portal1.test.gosuslugi.ru/rs/prns/";
	private String clientId = "RPGUGP";
	private String redirectUri = "http://localhost:8080/info";
	private String scope = "openid fullname";
	private String state = "";
	private String timeStamp;
	private boolean useOpenSSL = false;

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@RequestMapping("/")
	public String home() {
		try {
			Map<String, String> params = new LinkedHashMap<>();
			params.put("client_id", clientId);
			params.put("response_type", "code");
			params.put("redirect_uri", redirectUri);
			params.put("scope", scope);
			state = UUID.randomUUID().toString();
			params.put("state", state);
			params.put("access_type", "offline");
			DateFormat dateFormat = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss Z");
			timeStamp = dateFormat.format(new Date());
			params.put("timestamp", timeStamp);
			params.put("client_secret", sign(scope + timeStamp + clientId + state));

			return "<a href=\"" + authurl + getParamsString(params) + "\">Login</a>";

		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return "";
	}

	@RequestMapping("/info")
	public String info(@RequestParam("code") String code, @RequestParam("state") String state) {
		try {
			Map<String, String> params = new LinkedHashMap<>();
			params.put("client_id", clientId);
			params.put("code", code);
			params.put("grant_type", "authorization_code");
			params.put("redirect_uri", redirectUri);
			params.put("scope", scope);
			this.state = UUID.randomUUID().toString();
			params.put("state", this.state);
			DateFormat dateFormat = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss Z");
			timeStamp = dateFormat.format(new Date());
			params.put("timestamp", timeStamp);
			params.put("token_type", "Bearer");
			params.put("client_secret", sign(scope + timeStamp + clientId + this.state));

			byte[] postData = getParamsString(params).getBytes("UTF-8");
			URL objURL = new URL(tokenurl);
			HttpURLConnection con = (HttpURLConnection) objURL.openConnection();
			con.setRequestMethod("POST");
			con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			con.setRequestProperty("Content-Length", String.valueOf(postData.length));
			con.setUseCaches(false);
			con.setDoOutput(true);
			con.getOutputStream().write(postData);

			ObjectMapper mapper = new ObjectMapper();
			AuthResponse authResponse;
			try (InputStream is = con.getInputStream();) {
				authResponse = mapper.readValue(is, AuthResponse.class);
			}

			String[] tokenArray = authResponse.access_token.split("[.]");
			Payload payload = mapper.readValue(Base64.getUrlDecoder().decode(tokenArray[1]), Payload.class);

			objURL = new URL(resturl + payload.urn_esia_sbj_id);
			con = (HttpURLConnection) objURL.openConnection();
			con.setRequestMethod("GET");
			con.setRequestProperty("Authorization", "Bearer " + authResponse.access_token);
			con.setUseCaches(false);
			EsiaPerson esiaPerson;
			try (InputStream is = con.getInputStream();) {
				esiaPerson = mapper.readValue(is, EsiaPerson.class);
			}

			StringBuilder sb = new StringBuilder();
			sb.append(payload.urn_esia_sbj_id);
			sb.append("<br>");
			sb.append(esiaPerson.firstName);
			sb.append(" ");
			sb.append(esiaPerson.middleName);
			sb.append(" ");
			sb.append(esiaPerson.lastName);

			return sb.toString();

		} catch (Exception ex) {
			ex.printStackTrace();
		}

		return "";
	}

	private String sign(String input) {
		if (useOpenSSL)
			return signOpenSSL(input);
		else
			return signBouncyCastle(input);
	}

	private String signBouncyCastle(String input) {
		try {
			String strPK = "";
			BufferedReader br = new BufferedReader(new FileReader(keyPath));
			String line;
			while ((line = br.readLine()) != null) {
				strPK += line;
			}
			br.close();
			strPK = strPK.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
					.replace("\n", "");
			PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(strPK));
			PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(spec);
			CertificateFactory fact = CertificateFactory.getInstance("X.509");
			FileInputStream fis = new FileInputStream(cerPath);
			X509Certificate cer = (X509Certificate) fact.generateCertificate(fis);
			fis.close();
			return Base64.getUrlEncoder().encodeToString(signData(input.getBytes("UTF-8"), cer, pk));
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return "";
	}

	private String signOpenSSL(String input) {
		try {
			File inFile = File.createTempFile("text", ".msg");
			FileWriter fw = new FileWriter(inFile);
			fw.write(input);
			fw.close();
			File outFile = File.createTempFile("sign", ".msg");
			StringBuilder sb = new StringBuilder();
			sb.append("openssl smime -sign -md sha256 -in ");
			sb.append(inFile.getAbsolutePath());
			sb.append(" -signer ");
			sb.append(cerPath);
			sb.append(" -inkey ");
			sb.append(keyPath);
			sb.append(" -out ");
			sb.append(outFile.getAbsolutePath());
			sb.append(" -outform DER");
			Process proc = Runtime.getRuntime().exec(sb.toString());
			proc.waitFor();
			FileInputStream fis = new FileInputStream(outFile);
			byte[] s = new byte[fis.available()];
			fis.read(s, 0, s.length);
			fis.close();
			inFile.deleteOnExit();
			outFile.deleteOnExit();
			return Base64.getUrlEncoder().encodeToString(s);
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return "";
	}

	public static byte[] signData(byte[] data, final X509Certificate signingCertificate, final PrivateKey signingKey) {
		try {
			byte[] signedMessage = null;
			List<X509Certificate> certList = new ArrayList<X509Certificate>();
			CMSTypedData cmsData = new CMSProcessableByteArray(data);
			certList.add(signingCertificate);
			Store<?> certs = new JcaCertStore(certList);
			CMSSignedDataGenerator cmsGenerator = new CMSSignedDataGenerator();
			ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA").build(signingKey);
			Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
			cmsGenerator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
					new JcaDigestCalculatorProviderBuilder().setProvider("BC").build()).build(contentSigner,
							signingCertificate));
			cmsGenerator.addCertificates(certs);
			CMSSignedData cms = cmsGenerator.generate(cmsData, true);
			signedMessage = cms.getEncoded();
			return signedMessage;
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return new byte[0];

	}

	public static String getParamsString(Map<String, String> params) throws UnsupportedEncodingException {
		StringBuilder result = new StringBuilder();

		for (Map.Entry<String, String> entry : params.entrySet()) {
			result.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
			result.append("=");
			result.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
			result.append("&");
		}

		String resultString = result.toString();
		return resultString.length() > 0 ? resultString.substring(0, resultString.length() - 1) : resultString;
	}
}