package news;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.imageio.ImageIO;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.net.ProtocolCommandEvent;
import org.apache.commons.net.ProtocolCommandListener;
import org.apache.commons.net.nntp.NNTPClient;
import org.apache.commons.net.nntp.NewsgroupInfo;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * The root resource of our RESTful web service.
 */
@Path("/")
public class NewsService implements ProtocolCommandListener {

	private static boolean NZB = true; // parse nzb index files on the server?
	private static boolean HIDE_PAR_FILES = true;

	class ClientInfo {
		public NNTPClient client;
		public Timer timer;
		public TimerTask timerTask;
	}

	private static HashMap<String, Stack<ClientInfo>> clientPool = new HashMap<String, Stack<ClientInfo>>();

	private static Pattern multipartPattern = Pattern
			.compile("(.*)\\((\\d+)/(\\d+)\\)(.*)");
	private static Pattern multivolumePattern = Pattern
			.compile(".*\"(.*)\\.part(\\d+)\\.rar\".*");

	private Map<String, ArticleHeader[]> multipartMap = new HashMap<String, ArticleHeader[]>();
	private Map<String, ArticleHeader[]> multivolumeMap = new HashMap<String, ArticleHeader[]>();
	private String multipartMapNewsgroup = "";

	class FileInfo {
		public String filename;
		public int encoding;
		public String boundary;
	}

	private static int progressId = 0;
	private static HashMap<String, Progress> progressById = new HashMap<String, Progress>();

	private static final int CODE_NONE = 0;
	private static final int CODE_BASE64 = 1;
	private static final int CODE_UU = 2;
	private static final int CODE_YENC = 3;

	public NewsService() {
	}

	@POST
	@Produces(MediaType.TEXT_PLAIN)
	public String getInfo() {
		return "Usenet News (NNTP) REST Service";
	}

	@POST
	@Path("g")
	@Produces(MediaType.APPLICATION_JSON)
	public NewsgroupList getGroups(@FormParam("host") String host,
			@FormParam("pattern") String pattern, @FormParam("max") int max)
			throws SocketException, IOException {

		NNTPClient client = connect(host);

		NewsgroupInfo[] infos = client.listNewsgroups(pattern);

		if (infos == null)
			throw new IOException(client.getReplyString());

		disconnect(host, client);

		NewsgroupList list = new NewsgroupList();

		list.total = infos.length;

		for (int i = 0; i < infos.length && i < max; i++) {
			NewsgroupInfo info = infos[i];
			String name = info.getNewsgroup();
			list.newsgroups.add(new Newsgroup(name, info.getArticleCountLong(),
					info.getFirstArticleLong(), info.getLastArticleLong()));
		}

		return list;
	}

	@POST
	@Path("h")
	@Produces(MediaType.APPLICATION_JSON)
	public ArticleList getHeaders(@FormParam("host") String host,
			@FormParam("newsgroup") String newsgroup,
			@FormParam("filter") String filter, @FormParam("count") int count,
			@FormParam("offset") int offset) throws SocketException,
			IOException {

		filter = filter.trim();

		NNTPClient client = connect(host);

		NewsgroupInfo info = new NewsgroupInfo();

		client.selectNewsgroup(newsgroup, info);

		if (!newsgroup.equals(multipartMapNewsgroup)) {
			multipartMap.clear();
			multivolumeMap.clear();
		}

		long first = info.getFirstArticleLong();
		long last = info.getLastArticleLong() - offset;

		int blockSize = count * 2;

		long high = last;
		long low = Math.max(high - blockSize + 1, first);

		ArticleList list = new ArticleList();
		int start = 0;

		while (true) {
			BufferedReader reader = client.retrieveArticleInfo(low, high);

			String line;
			while ((line = reader.readLine()) != null) {
				if (list.articles.size() < count) {
					String[] s = line.split("\t");
					String subject = s[1];
					String id = s[4];
					int bytes = s[6].length() > 0 ? Integer.parseInt(s[6]) : 0;
					ArticleHeader header = new ArticleHeader(subject, id);
					header.bytes = bytes;

					if (!computeMultipart(header, multipartMap)
							&& header.parts != "incomplete"
							&& !isHidden(subject, filter))
						list.articles.add(start, header);

					offset++;
				}
			}

			computeMultivolumes(list.articles);

			if (list.articles.size() >= count || low <= first)
				break;

			high = Math.max(high - blockSize, first);
			low = Math.max(high - blockSize + 1, first);

			start = list.articles.size();
		}

		list.available = low - first;
		list.offset = offset;

		disconnect(host, client);

		return list;
	}

	@POST
	@Path("b")
	@Produces(MediaType.APPLICATION_JSON)
	public ArticleBody getBody(@FormParam("host") String host,
			@FormParam("articleId") String articleId) throws SocketException,
			IOException, ParserConfigurationException, SAXException {
		return getBody(host, articleId, null);
	}

	private static class ProgressByteArrayOutputStream extends
			ByteArrayOutputStream {
		private ByteArrayOutputStream chunk = new ByteArrayOutputStream();

		boolean cancelled;
		boolean noChunks;
		private long lastTime = System.currentTimeMillis();
		private int maxChunkSize = 10000;

		public synchronized void write(int c) {
			if (!cancelled) {
				super.write(c);
				if (!noChunks) {
					chunk.write(c);
					waitForClient();
				}
			}
		}

		public synchronized void write(byte[] bytes) throws IOException {
			if (!cancelled) {
				super.write(bytes);
				if (!noChunks) {
					chunk.write(bytes);
					waitForClient();
				}
			}
		}

		private synchronized void waitForClient() {
			while (chunk.size() > maxChunkSize) {
				try {
					wait();
				} catch (InterruptedException e) {
				}
			}
		}

		public synchronized byte[] getChunkBytes() {

			long t = System.currentTimeMillis();
			if (t - lastTime < 500) {
				maxChunkSize = Math.min(1000000, maxChunkSize * 2);
			}
			if (chunk.size() > 0) {
				lastTime = t;
			}

			byte[] bytes = chunk.toByteArray();
			chunk.reset();
			notify();
			return bytes;
		}

		void cancel() {
			cancelled = true;
			reset();
			chunk.reset();
		}
	}

	@XmlRootElement
	public static class Progress {
		@XmlAttribute
		public boolean complete;
		@XmlAttribute
		public int bytesRead;
		@XmlAttribute
		public int part;
		@XmlAttribute
		public int partCount;
		@XmlAttribute
		public String exception;
		@XmlAttribute
		public byte[] chunk;
		@XmlAttribute
		public String filename;
		@XmlAttribute
		public String message;
		@XmlElement
		public ArticleBody body;
		@XmlElement
		public ArrayList<Integer> attSizes;

		boolean cancelled;
		boolean updated;
		byte[] thumbnailData;
		int thumbnailSize;

		private ProgressByteArrayOutputStream buffer;

		public ProgressByteArrayOutputStream getBuffer() {
			if (buffer == null)
				buffer = new ProgressByteArrayOutputStream();
			else
				buffer.reset();
			if (thumbnailSize > 0) {
				buffer.noChunks = true;
			}
			return buffer;
		}

		public void getProgress() throws InterruptedException {
			// estimate connection speed and adjust max chunk size if necessary:
			if (buffer != null) {
				if (cancelled) {
					buffer.cancel();
					return;
				}
				synchronized (buffer) {
					if (filename != null) {
						if (thumbnailSize > 0) {
							if (isImage(filename)) {
								byte[] data = buffer.toByteArray();
								if (thumbnailData != null) {
									byte[] d = new byte[thumbnailData.length
											+ data.length];
									System.arraycopy(thumbnailData, 0, d, 0,
											thumbnailData.length);
									System.arraycopy(data, 0, d,
											thumbnailData.length, data.length);
									data = d;
								}
								chunk = createThumbnail(data, thumbnailSize);
							}
							return;
						}
						byte[] bytes = buffer.getChunkBytes();
						chunk = bytes;
					}
				}
			}
		}

		void attachmentDecoded(Attachment att, int part, ArticleBody[] bodies) {
			if (thumbnailSize > 0 && isImage(filename) && bodies.length > 1) {
				thumbnailData = concatData(att, part, bodies);
			}
		}

		private byte[] concatData(Attachment att, int part, ArticleBody[] bodies) {
			byte[] data;
			if (bodies.length == 1 || part == 0) {
				data = att.data;
			} else {
				ArrayList<byte[]> dl = new ArrayList<byte[]>();
				for (int i = 0; i < part; i++) {
					ArrayList<Attachment> atts = bodies[i].attachments;
					if (atts != null && atts.size() > 0) {
						byte[] b = atts.get(0).data;
						if (b != null) {
							dl.add(b);
						}
					}
				}
				int size = 0;
				for (int i = 0; i < dl.size(); i++) {
					size += dl.get(i).length;
				}
				size += att.data.length;
				data = new byte[size];
				int pos = 0;
				for (int i = 0; i < dl.size(); i++) {
					byte[] d = dl.get(i);
					System.arraycopy(d, 0, data, pos, d.length);
					pos += d.length;
				}
				System.arraycopy(att.data, 0, data, pos, att.data.length);
			}
			return data;
		}
	}

	private static class ProgressReader extends BufferedReader {

		private Progress progress;

		public ProgressReader(BufferedReader reader, Progress progress) {
			super(reader);
			this.progress = progress;
		}

		public ByteArrayOutputStream getBuffer() {
			return progress.getBuffer();
		}

		public String readLine() throws IOException {
			String line = super.readLine();
			if (line != null) {
				synchronized (progress) {
					progress.bytesRead += line.length() + 2;
					progress.updated = true;
					progress.notify();
				}
			}
			return line;
		}
	}

	public ArticleBody getBody(final String host, String articleId,
			Progress progress) throws SocketException, IOException,
			ParserConfigurationException, SAXException {

		String[] aids = articleId.split(",");

		NNTPClient client = connect(host);

		ArticleBody[] bodies = new ArticleBody[aids.length];

		if (progress != null) {
			progress.partCount = aids.length;
		}

		FileInfo fileInfo = new FileInfo();

		for (int i = 0; i < aids.length; i++) {

			if (progress != null) {
				progress.part = i + 1;
			}

			String aid = aids[i];

			if (aid.charAt(0) != '<')
				aid = "<" + aid;
			if (aid.charAt(aid.length() - 1) != '>')
				aid = aid + ">";

			BufferedReader reader = (BufferedReader) client
					.retrieveArticle(aid);

			if (reader == null)
				throw new IOException(client.getReplyString());

			if (progress != null) {
				reader = new ProgressReader(reader, progress);
			}

			readHeaders(reader, fileInfo);

			ArticleBody part = new ArticleBody();

			readBody(reader, part, i, bodies, fileInfo);

			bodies[i] = part;
		}

		disconnect(host, client);

		ArticleBody body;

		if (aids.length == 1) {
			body = bodies[0];
		} else {
			body = new ArticleBody();
			body.text = "";
			for (int i = 0; i < aids.length; i++) {
				ArticleBody b = bodies[i];
				body.text += b.text;
				for (int j = 0; j < b.attachments.size(); j++) {
					Attachment atti = b.attachments.get(j);
					Attachment att;
					if (j >= body.attachments.size()) {
						att = new Attachment();
						att.filename = atti.filename;
						att.data = atti.data;
						body.attachments.add(att);
					} else {
						att = body.attachments.get(j);
						byte[] data = new byte[att.data.length
								+ atti.data.length];
						System.arraycopy(att.data, 0, data, 0, att.data.length);
						System.arraycopy(atti.data, 0, data, att.data.length,
								atti.data.length);
						att.data = data;
					}
				}
			}
		}

		if (progress != null && progress.cancelled) {
			return body;
		}

		if (body.attachments.size() == 1) {
			Attachment a = body.attachments.get(0);
			if (a.filename != null) {
				if (NZB && a.filename.endsWith(".nzb")) {
					// parse NZB index files
					if (progress != null) {
						progress.message = "Parsing NZB index...";
						Thread.yield();
					}
					body.articles = new ArrayList<ArticleHeader>();
					parseNzb(new ByteArrayInputStream(a.data), "",
							body.articles);
				}
			}
		}

		return body;
	}

	@POST
	@Path("ba")
	@Produces(MediaType.TEXT_PLAIN)
	public String getBodyAsync(@FormParam("host") final String host,
			@FormParam("articleId") final String articleId)
			throws SocketException, IOException {

		final Progress progress = new Progress();

		final String id = String.valueOf(++progressId);
		progressById.put(id, progress);

		new Thread() {
			public void run() {
				ArticleBody body;
				try {
					body = getBody(host, articleId, progress);
					progress.body = progress.chunk != null ? body
							.cloneWithoutData() : body;
				} catch (Throwable ex) {
					progress.exception = ex.getMessage();
				}
				synchronized (progress) {
					progress.complete = true;
					progress.updated = true;
					progress.notify();
				}
			}
		}.start();

		return id;
	}

	@POST
	@Path("p")
	@Produces(MediaType.APPLICATION_JSON)
	public Progress getProgress(@FormParam("id") final String id)
			throws SocketException, IOException, InterruptedException {
		Progress progress = progressById.get(id);
		if (progress != null) {
			synchronized (progress) {
				while (!progress.updated) {
					progress.wait();
				}
				progress.getProgress();
				if (progress.complete) {
					progressById.remove(id);
				}
				progress.updated = false;
				return progress;
			}
		}
		return null;
	}

	@POST
	@Path("c")
	@Produces(MediaType.TEXT_PLAIN)
	public String cancel(@FormParam("id") final String id)
			throws SocketException, IOException, InterruptedException {
		Progress progress = progressById.get(id);
		if (progress != null) {
			progress.cancelled = true;
			progressById.remove(id);
		}
		return "OK";
	}

	@POST
	@Path("t")
	@Produces("text/plain")
	public String testServer(@FormParam("host") String host)
			throws SocketException, IOException {
		NNTPClient client = connect(host);
		String reply = client.getReplyString();
		disconnect(host, client);
		return reply;
	}

	private NNTPClient connect(String host) throws SocketException, IOException {

		Stack<ClientInfo> clients = clientPool.get(host);
		if (clients != null && clients.size() > 0) {
			do {
				ClientInfo clientInfo = clients.pop();
				clientInfo.timer.cancel();
				if (clientInfo.client.isAvailable()
						&& clientInfo.client.isConnected()) {
					return clientInfo.client;
				}
			} while (clients.size() > 0);
		}

		int port = NNTPClient.DEFAULT_PORT;

		int portIndex = host.lastIndexOf(':');
		if (portIndex >= 0) {
			String portString = host.substring(portIndex + 1);
			if (portString.matches("\\d*")) {
				port = Integer.valueOf(portString);
				host = host.substring(0, portIndex);
			}
		}

		String username = null;
		String password = null;

		int userIndex = host.lastIndexOf('@');
		if (userIndex >= 0) {
			username = host.substring(0, userIndex);
			int passIndex = username.indexOf(':');
			if (passIndex >= 0) {
				password = username.substring(passIndex + 1);
				username = username.substring(0, passIndex);
			} else {
				password = "";
			}
			host = host.substring(userIndex + 1);
		}

		InetAddress addr = InetAddress.getByName(host);

		NNTPClient client = new NNTPClient();

		client.addProtocolCommandListener(this);

		client.connect(addr, port);

		if (username != null)
			client.authenticate(username, password);

		return client;
	}

	private void disconnect(final String host, NNTPClient client) {
		if (client != null) {
			Stack<ClientInfo> clients = clientPool.get(host);
			if (clients == null) {
				clients = new Stack<ClientInfo>();
				clientPool.put(host, clients);
			}
			final ClientInfo clientInfo = new ClientInfo();
			clientInfo.client = client;
			clientInfo.timer = new Timer();
			clientInfo.timer.schedule(new TimerTask() {
				public void run() {
					try {
						clientInfo.client.quit();
					} catch (Exception e) {
					}
					try {
						clientInfo.client.disconnect();
					} catch (Exception e) {
					}
					clientPool.get(host).remove(clientInfo);
				}
			}, 5 * 60 * 1000); // disconnect after 5mn inactive
			clients.push(clientInfo);
		}
	}

	public void protocolCommandSent(ProtocolCommandEvent event) {
	}

	public void protocolReplyReceived(ProtocolCommandEvent event) {
		if (event.getReplyCode() >= 400)
			throw new NewsException(event.getMessage());
	}

	private FileInfo readHeaders(BufferedReader reader, FileInfo fileInfo)
			throws IOException {
		String wrappedLine = "";
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.length() > 0
					&& (line.charAt(0) == '\t' || line.charAt(0) == ' ')) {
				wrappedLine += line.substring(1);
			} else {
				if (wrappedLine.length() > 0) {
					String[] s = wrappedLine.split("[ \t]*:[ \t]*", 2);
					if (s.length == 2) {
						String h = s[0].toLowerCase();
						if (h.equals("content-transfer-encoding")) {
							String v = s[1].toLowerCase();
							if (v.equals("base64"))
								fileInfo.encoding = CODE_BASE64;
						} else if (h.equals("content-type")) {
							String[] ct = s[1].split("[ \t]*;[ \t]*");
							if (ct.length > 0) {
								for (int j = 1; j < ct.length; j++) {
									String[] p = ct[j].split("=", 2);
									if (p.length == 2) {
										if (p[0].equals("name"))
											fileInfo.filename = trimQuotes(p[1]);
										else if (p[0].equals("boundary")
												&& ct[0].equals("multipart/mixed"))
											fileInfo.boundary = "--"
													+ trimQuotes(p[1]);
									}
								}
							}
						}
					}
				}
				if (line.length() == 0)
					break;
				wrappedLine = line;
			}
		}
		return fileInfo;
	}

	private String trimQuotes(String s) {
		if (s.length() >= 2 && s.charAt(0) == '"'
				&& s.charAt(s.length() - 1) == '"')
			s = s.substring(1, s.length() - 1);
		return s;
	}

	private void readBody(BufferedReader reader, ArticleBody body, int part,
			ArticleBody[] bodies, FileInfo fileInfo) throws IOException {

		StringBuffer text = new StringBuffer();

		if (fileInfo.encoding != CODE_NONE)
			decode(reader, body, text, part, bodies, fileInfo);

		String line;
		while ((line = reader.readLine()) != null) {

			if (fileInfo.boundary != null && line.startsWith("--")) {
				if (line.equals(fileInfo.boundary)) {
					fileInfo.encoding = CODE_NONE;
					fileInfo.filename = null;
					readHeaders(reader, fileInfo);
					if (fileInfo.encoding != CODE_NONE)
						decode(reader, body, text, part, bodies, fileInfo);
					continue;
				}
				if (line.equals(fileInfo.boundary + "--")) {
					continue;
				}
			}

			if (line.startsWith("=ybegin ")) {
				fileInfo.encoding = CODE_YENC;
				int nameIndex = line.indexOf("name=");
				if (nameIndex > 0)
					fileInfo.filename = line.substring(nameIndex + 5);
				else
					fileInfo.filename = "(unnamed)";
			} else if (line.startsWith("begin ")) {
				fileInfo.encoding = CODE_UU;
				int nameIndex = line.lastIndexOf(" ");
				if (nameIndex > 0)
					fileInfo.filename = line.substring(nameIndex + 1);
				else
					fileInfo.filename = "(unnamed)";
			}

			if (fileInfo.encoding != CODE_NONE) {
				decode(reader, body, text, part, bodies, fileInfo);
			} else {
				text.append(line);
				text.append('\n');
			}
		}

		body.text = text.toString();
		body.size += text.length();
	}

	private void decode(BufferedReader reader, ArticleBody body,
			StringBuffer text, int part, ArticleBody[] bodies, FileInfo fileInfo)
			throws IOException {

		ByteArrayOutputStream bytes;
		if (reader instanceof ProgressReader) {
			bytes = ((ProgressReader) reader).getBuffer();
			Progress p = ((ProgressReader) reader).progress;
			if (fileInfo.filename != null)
				p.filename = fileInfo.filename;
			if (body.attachments.size() > 0) {
				// multiple attachments
				p.attSizes = new ArrayList<Integer>();
				for (int i = 0; i < body.attachments.size(); i++) {
					p.attSizes.add(body.attachments.get(i).data.length);
				}
			}
		} else {
			bytes = new ByteArrayOutputStream();
		}

		if (fileInfo.encoding == CODE_YENC) {
			ydecode(reader, fileInfo, bytes);
		} else if (fileInfo.encoding == CODE_UU) {
			uudecode(reader, fileInfo, bytes);
		} else if (fileInfo.encoding == CODE_BASE64) {
			base64decode(reader, fileInfo, bytes);
		} else {
			throw new Error("Unknown content encoding " + fileInfo.encoding);
		}

		byte[] data = bytes.toByteArray();
		bytes.reset();

		if (data.length > 0) {
			Attachment att = new Attachment(fileInfo.filename, data);
			body.attachments.add(att);
			body.size += data.length;
			if (reader instanceof ProgressReader) {
				// notify Progress, e.g. to send a thumbnail
				Progress progress = ((ProgressReader) reader).progress;
				progress.attachmentDecoded(att, part, bodies);
			}
		}
	}

	private void ydecode(BufferedReader reader, FileInfo fileInfo,
			ByteArrayOutputStream bytes) throws IOException {

		String line;
		while ((line = reader.readLine()) != null) {

			int lineLength = line.length();

			if (lineLength == 0)
				continue;
			if (fileInfo.boundary != null && line.charAt(0) == '-'
					&& line.equals(fileInfo.boundary + "--"))
				break;
			if (line.charAt(0) == '=') {
				if (line.startsWith("=yend "))
					break;
				if (line.startsWith("=ypart "))
					continue;
				if (line.startsWith("=ybegin "))
					continue;
			}

			int i = 0;

			if (lineLength > 0 && line.charAt(0) == '.') {
				if (lineLength == 1)
					break;
				if (lineLength == 2 && line.charAt(1) == '.')
					i++;
			}

			while (i < lineLength) {
				char c = line.charAt(i++);
				if (c == '=') {
					c = line.charAt(i++);
					c = (char) ((c - 64) % 256);
				}
				c = (char) ((c - 42) % 256);
				bytes.write(c);
			}
		}
		fileInfo.encoding = CODE_NONE;
	}

	private void uudecode(BufferedReader reader, FileInfo fileInfo,
			ByteArrayOutputStream bytes) throws IOException {

		String line;
		while ((line = reader.readLine()) != null) {

			if (line.equals("end")) {
				fileInfo.encoding = CODE_NONE;
				break;
			}

			if (line.length() == 0)
				continue;

			if (fileInfo.boundary != null && line.charAt(0) == '-'
					&& line.equals(fileInfo.boundary + "--"))
				break;

			int count = line.charAt(0) - 32;
			int l = line.length();
			for (int i = 1; i < l; i += 4) {
				int c0 = line.charAt(i) - 32;
				int c1 = i + 1 < l ? line.charAt(i + 1) - 32 : 0;
				int c2 = i + 2 < l ? line.charAt(i + 2) - 32 : 0;
				int c3 = i + 3 < l ? line.charAt(i + 3) - 32 : 0;

				int d0 = ((c0 << 2) & 0xfc) | ((c1 >> 4) & 0x3);
				int d1 = ((c1 << 4) & 0xf0) | ((c2 >> 2) & 0xf);
				int d2 = ((c2 << 6) & 0xc0) | ((c3) & 0x3f);

				if (count > 0)
					bytes.write(d0);
				if (count > 1)
					bytes.write(d1);
				if (count > 2)
					bytes.write(d2);

				count -= 3;
			}
		}
	}

	private void base64decode(BufferedReader reader, FileInfo fileInfo,
			ByteArrayOutputStream bytes) throws IOException {

		String line;
		while ((line = reader.readLine()) != null) {

			if (line.length() == 0)
				continue;

			if (fileInfo.boundary != null && line.charAt(0) == '-'
					&& line.equals(fileInfo.boundary + "--"))
				break;

			for (int i = 0; i < line.length(); i += 4) {
				int c1 = line.charAt(i);
				int c2 = line.charAt(i + 1);
				int c3 = line.charAt(i + 2);
				int c4 = line.charAt(i + 3);
				int padding = 0;
				if (c4 == '=') {
					padding++;
					c4 = 'A';
				}
				if (c3 == '=') {
					padding++;
					c3 = 'A';
				}
				c1 = base64char(c1);
				c2 = base64char(c2);
				c3 = base64char(c3);
				c4 = base64char(c4);
				int v = ((c1 << 18) & 0x1fc0000) | ((c2 << 12) & 0x3f000)
						| ((c3 << 6) & 0xfc0) | (c4 & 0x3f);
				bytes.write((v >> 16) & 0xff);
				if (padding < 1)
					bytes.write((v >> 8) & 0xff);
				if (padding < 2)
					bytes.write(v & 0xff);
			}
		}
	}

	private int base64char(int c) {
		if (c >= 'A' && c <= 'Z')
			return c - 'A';
		if (c >= 'a' && c <= 'z')
			return c - 'a' + 26;
		if (c >= '0' && c <= '9')
			return c - '0' + 52;
		if (c == '+')
			return 62;
		if (c == '/')
			return 63;
		throw new Error("Invalid base64 character: " + c);
	}

	private boolean computeMultipart(ArticleHeader article,
			Map<String, ArticleHeader[]> map) {

		Matcher m = multipartPattern.matcher(article.subject);
		if (m.matches() && m.groupCount() == 4) {
			int partNumber = Integer.parseInt(m.group(2));
			int partCount = Integer.parseInt(m.group(3));

			if (partCount != 1 && partNumber != 0) {

				String prefix = m.group(1);
				String suffix = m.group(4);

				String key = prefix + "X/" + partCount + suffix;

				ArticleHeader[] parts = map.get(key);
				if (parts == null) {
					parts = new ArticleHeader[partCount];
					map.put(key, parts);
				}
				parts[partNumber - 1] = article;

				if (parts[0] != null) {
					parts[0].parts = "";
					for (int i = 0; i < parts.length; i++) {
						if (parts[i] == null) {
							parts[0].parts = "incomplete";
							break;
						}
						if (i > 0) {
							parts[0].parts += ",";
							parts[0].bytes += parts[i].bytes;
						}
						parts[0].parts += parts[i].articleId;
					}
				}

				return partNumber > 1;
			}
		}

		return false;
	}

	private void computeMultivolumes(List<ArticleHeader> list) {

		Map<String, ArticleHeader[]> map = multivolumeMap;

		for (int j = 0; j < list.size(); j++) {

			ArticleHeader article = list.get(j);

			Matcher m = multivolumePattern.matcher(article.subject);
			if (m.matches() && m.groupCount() == 2) {

				String file = m.group(1);
				String n = m.group(2);
				int partNumber = Integer.parseInt(n);
				int partCount = (int) Math.pow(10, n.length());

				String key = file;

				ArticleHeader[] parts = map.get(key);
				if (parts == null) {
					parts = new ArticleHeader[partCount];
					map.put(key, parts);
				}
				parts[partNumber - 1] = article;

				if (parts[0] != null) {
					parts[0].vols = "";
					for (int i = 0; i < parts.length; i++) {
						if (parts[i] == null) {
							break;
						}
						if (i > 0) {
							parts[0].vols += ",";
							parts[0].bytes += parts[i].bytes;
						}
						parts[0].vols += parts[i].parts;
					}
				}

				if (partNumber > 1) {
					list.remove(j--);
				}
			}
		}
	}

	// ---------------
	// Search
	// ---------------

	@POST
	@Path("s")
	@Produces(MediaType.APPLICATION_JSON)
	public ArticleList getNzb(@FormParam("pattern") String pattern,
			@FormParam("filter") String filter, @FormParam("max") int max,
			@FormParam("age") int age, @FormParam("server") int server,
			@FormParam("offset") int offset) throws MalformedURLException,
			IOException, ParserConfigurationException, SAXException {

		filter = filter.trim();

		String host = "http://www.binsearch.info";
		String req = "/?q="
				+ URLEncoder.encode(pattern + " " + filter, "UTF-8") + "&max="
				+ max + "&adv_age=" + age + "&server=" + server;
		if (offset > 0)
			req += "&min=" + offset;

		String url = host + req;

		HttpURLConnection conn = (HttpURLConnection) new URL(url)
				.openConnection();
		conn.setRequestMethod("GET");
		conn.setRequestProperty("Accept", "text/html");
		conn.setRequestProperty("Accept-Encoding", "gzip");
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

		ArticleList list = new ArticleList();

		if (names.size() > 0) {

			String nzburl = host + "/fcgi/nzb.fcgi" + req;

			conn = (HttpURLConnection) new URL(nzburl).openConnection();
			conn.setRequestProperty("Accept", "text/html");
			conn.setRequestProperty("Accept-Encoding", "gzip");
			conn.setRequestProperty("Content-Type",
					"application/x-www-form-urlencoded");
			conn.setRequestProperty("Referer", url);
			conn.setRequestProperty("Origin", host);
			conn.setDoOutput(true);
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
					conn.getOutputStream()));
			for (int i = 0; i < names.size(); i++) {
				writer.write(names.get(i) + "=on&");
			}
			writer.write("action=nzb");
			writer.flush();

			gzip = new GZIPInputStream(conn.getInputStream());

			parseNzb(gzip, filter, list.articles);

			writer.close();
			gzip.close();

		}

		list.available = available ? max : 0;
		list.offset = offset + max + 1;

		return list;
	}

	private void parseNzb(InputStream in, String filter,
			List<ArticleHeader> articles) throws ParserConfigurationException,
			SAXException, IOException {

		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

		dbf.setValidating(false);
		dbf.setFeature("http://xml.org/sax/features/namespaces", false);
		dbf.setFeature("http://xml.org/sax/features/validation", false);
		dbf.setFeature(
				"http://apache.org/xml/features/nonvalidating/load-dtd-grammar",
				false);
		dbf.setFeature(
				"http://apache.org/xml/features/nonvalidating/load-external-dtd",
				false);

		DocumentBuilder builder = dbf.newDocumentBuilder();

		Document doc = builder.parse(in);

		NodeList files = doc.getElementsByTagName("file");
		for (int i = 0; i < files.getLength(); i++) {
			Element file = (Element) files.item(i);
			String subject = file.getAttribute("subject");
			if (isHidden(subject, filter)) {
				continue;
			}
			ArticleHeader article = new ArticleHeader(subject, null);
			NodeList segments = file.getElementsByTagName("segments");
			if (segments.getLength() > 0) {
				segments = ((Element) segments.item(0))
						.getElementsByTagName("segment");
				if (segments.getLength() > 0) {
					String[] ids = new String[segments.getLength()];
					for (int j = 0; j < segments.getLength(); j++) {
						Element segment = (Element) segments.item(j);
						int number = Integer.parseInt(segment
								.getAttribute("number")) - 1;
						if (number >= 0 && number < ids.length)
							ids[number] = segment.getTextContent();
						article.bytes += Integer.parseInt(segment
								.getAttribute("bytes"));
					}
					String parts = ids[0];
					for (int j = 1; j < ids.length; j++) {
						parts += "," + ids[j];
					}
					article.parts = parts;
				}
			}
			articles.add(article);
		}
		computeMultivolumes(articles);
	}

	// ------------------------------
	// Index thumbnails
	// ------------------------------

	@POST
	@Path("th")
	@Produces(MediaType.APPLICATION_JSON)
	public ArticleBody getThumbnail(@FormParam("host") String host,
			@FormParam("articleId") String articleId,
			@FormParam("size") int size) throws SocketException, IOException,
			ParserConfigurationException, SAXException {
		return getThumbnail(host, articleId, size, null);
	}

	@POST
	@Path("tha")
	@Produces(MediaType.APPLICATION_JSON)
	public String getThumbnailAsync(@FormParam("host") final String host,
			@FormParam("articleId") final String articleId,
			@FormParam("size") final int size) throws SocketException,
			IOException, ParserConfigurationException, SAXException {

		final Progress progress = new Progress();
		progress.thumbnailSize = size;

		final String id = String.valueOf(++progressId);
		progressById.put(id, progress);

		new Thread() {
			public void run() {
				try {
					progress.body = getThumbnail(host, articleId, size,
							progress);
				} catch (Throwable ex) {
					progress.exception = ex.getMessage();
				}
				synchronized (progress) {
					progress.complete = true;
					progress.updated = true;
					progress.notify();
				}
			}
		}.start();

		return id;
	}

	private ArticleBody getThumbnail(String host, String articleId, int size,
			Progress progress) throws SocketException, IOException,
			ParserConfigurationException, SAXException {

		ArticleBody body = getBody(host, articleId, progress);

		ArticleBody thumbnailBody = new ArticleBody();
		thumbnailBody.text = "-- Thumbnail --\n\n" + body.text;

		for (int i = 0; i < body.attachments.size(); i++) {
			Attachment a = body.attachments.get(i);
			if (isImage(a.filename)) {
				byte[] data = createThumbnail(a.data, size);
				if (data != null) {
					thumbnailBody.attachments.add(new Attachment(a.filename,
							data));
				}
			}
		}

		return thumbnailBody;
	}

	// ---------
	// Utilities
	// ---------

	private static boolean isImage(String filename) {
		String name = filename.toLowerCase();
		return name.endsWith(".jpg") || name.endsWith(".gif")
				|| name.endsWith(".png");
	}

	private boolean isHidden(String subject, String filter) {
		if (!(filter.isEmpty() || subject.contains(filter))) {
			return true;
		}
		if (HIDE_PAR_FILES) {
			subject = subject.toLowerCase();
			if (subject.contains(".par\"") || subject.contains(".par2\""))
				return true;
		}
		return false;
	}

	private static byte[] createThumbnail(byte[] data, int size) {
		BufferedImage image;
		try {
			image = ImageIO.read(new ByteArrayInputStream(data));
			int iw = image.getWidth();
			int ih = image.getHeight();
			int tw = size, th = size;
			if (ih > iw) {
				tw = th * iw / ih;
			} else {
				th = tw * ih / iw;
			}
			BufferedImage thumbnail = new BufferedImage(tw, th,
					image.getType() == 0 ? BufferedImage.TYPE_INT_RGB
							: image.getType());
			Graphics2D g = thumbnail.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING,
					RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
			g.drawImage(image, 0, 0, tw, th, null);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(thumbnail, "jpg", out);
			return out.toByteArray();
		} catch (Exception e) {
			return null;
		}
	}
}
