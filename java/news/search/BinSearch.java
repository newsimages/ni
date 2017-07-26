package news.search;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;

public class BinSearch extends SearchEngine {

	private int server;
	
	protected BinSearch(int server) {
		this.server = server;
	}
	
	public SearchEngine.Result search(String pattern, String filter, int max,
			int age, int offset) throws IOException {
		String host = "https://www.binsearch.info";
		String req = "/?q="
				+ URLEncoder.encode(pattern + " " + filter, "UTF-8") + "&max="
				+ max + "&adv_age=" + age + "&server=" + server;
		if (offset > 0)
			req += "&min=" + offset;

		String url = host + req;

		HttpsURLConnection conn = (HttpsURLConnection) new URL(url)
				.openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("Accept", "text/html");
		conn.setRequestProperty("Host", "www.binsearch.info");
		conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/56.0.2924.87 Safari/537.36");
		conn.setRequestProperty("Accept-Encoding", "gzip");
		
		String cookie = conn.getHeaderField("Set-Cookie").split(";")[0];
		
		GZIPInputStream gzip = new GZIPInputStream(conn.getInputStream());
		BufferedReader reader = new BufferedReader(new InputStreamReader(gzip));

		String checkBox = "<input type=\"checkbox\" name=\"";
		String records = "+ records <a";

		ArrayList<String> names = new ArrayList<String>();

		boolean available = false;

		String line;
		while ((line = reader.readLine()) != null) {
			for (int i = line.indexOf(checkBox); i >= 0; i = line.indexOf(
					checkBox, i)) {
				i += checkBox.length();
				int j = line.indexOf('"', i);
				if (j > 0) {
					String name = line.substring(i, j);
					if (!name.equals("00000001")) {
						names.add(name);
					}
					if (names.size() >= 200)
						break;
				}
			}
			if (line.indexOf(records) > 0)
				available = true;
		}

		reader.close();

		if (names.size() > 0) {

			String nzburl = host + "/fcgi/nzb.fcgi" + req;

			conn = (HttpsURLConnection) new URL(nzburl).openConnection();
			conn.setRequestProperty("Accept", "text/html");
			conn.setRequestProperty("Accept-Encoding", "gzip");
			conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			conn.setRequestProperty("Referer", url);
			conn.setRequestProperty("Origin", host);
			conn.setRequestProperty("Cookie", cookie);
			conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/56.0.2924.87 Safari/537.36");
			conn.setDoOutput(true);
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
					conn.getOutputStream()));
			for (int i = 0; i < names.size(); i++) {
				writer.write(names.get(i) + "=on&");
			}
			writer.write("action=nzb");
			writer.flush();
			writer.close();

			return new SearchEngine.Result(new GZIPInputStream(conn.getInputStream()), available);
		}
		
		return null;
	}
}
