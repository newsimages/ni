package news.search;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;

public class NZBIndex extends SearchEngine {

	public SearchEngine.Result search(String pattern, String filter, int max,
			int age, int offset) throws IOException {
		
		String host = "http://www.nzbindex.com";
		String pat = pattern + " " + filter;
		pat = pat.replace(' ',  '+');
		//pat = URLEncoder.encode(pat, "UTF-8");
		int p = (offset/max);
		String req = "/search/?q="
				+ pat + "&sort=agedesc&max="
				+ max + "&p=" + p;

		String url = host + req;

		HttpURLConnection conn = (HttpURLConnection) new URL(url)
				.openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("Accept", "text/html");
		conn.setRequestProperty("Accept-Encoding", "gzip");
		conn.setRequestProperty("Cookie", "agreed=true");
		GZIPInputStream gzip = new GZIPInputStream(conn.getInputStream());
		BufferedReader reader = new BufferedReader(new InputStreamReader(gzip));

		String checkBox = "<input type=\"checkbox\" id=\"box";
		String next = "<b>" + (p+1) + "</b>";

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
			if (line.indexOf(next) > 0)
				available = true;
		}

		reader.close();

		if (names.size() > 0) {

			String nzburl = host + "/download";

			conn = (HttpURLConnection) new URL(nzburl).openConnection();
			conn.setRequestProperty("Accept", "text/html");
			conn.setRequestProperty("Accept-Encoding", "gzip");
			conn.setRequestProperty("Content-Type",
					"application/x-www-form-urlencoded");
			conn.setRequestProperty("Referer", url);
			conn.setRequestProperty("Origin", host);
			conn.setRequestProperty("Cookie", "agreed=true");
			conn.setDoOutput(true);
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
					conn.getOutputStream()));
			writer.write("n=" + pat);
			for (int i = 0; i < names.size(); i++) {
				writer.write("&r%5B%5D=" + names.get(i));
			}
			writer.flush();
			writer.close();

			return new SearchEngine.Result(new GZIPInputStream(conn.getInputStream()), available);
		}
		
		return null;
	}
}
