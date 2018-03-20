package news;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.Timer;
import java.util.TimerTask;

import javax.imageio.ImageIO;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.net.ProtocolCommandEvent;
import org.apache.commons.net.ProtocolCommandListener;
import org.apache.commons.net.nntp.NNTPClient;
import org.apache.commons.net.nntp.NewsgroupInfo;
import org.apache.wink.common.model.multipart.BufferedOutMultiPart;
import org.apache.wink.common.model.multipart.OutMultiPart;
import org.apache.wink.common.model.multipart.OutPart;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import de.innosystec.unrar.Archive;
import de.innosystec.unrar.NativeStorage;
import de.innosystec.unrar.exception.RarException;
import de.innosystec.unrar.rarfile.FileHeader;
import news.HeaderGroupMatcher.HeaderGroupMatchResult;
import news.cache.BodyCache;
import news.cache.CacheInfo;
import news.cache.ReaderCache;
import news.search.SearchEngine;

/**
 * The root resource of our RESTful web service.
 */
@Path("/")
public class NewsService implements ProtocolCommandListener {

	private static boolean NZB = true; // parse nzb index files on the server?
	private static boolean RAR = true; // unpack RAR files on the server?
	private static boolean HIDE_PAR_FILES = true;

	class ClientInfo {
		public NNTPClient client;
		public Timer timer;
		public TimerTask timerTask;
	}

	private static HashMap<String, Stack<ClientInfo>> clientPool = new HashMap<String, Stack<ClientInfo>>();

	private static Map<String, ArticleHeader[]> multipartMap = new HashMap<String, ArticleHeader[]>();
	private static Map<String, ArticleHeader[]> multivolumeMap = new HashMap<String, ArticleHeader[]>();
	private static Map<String, ArticleHeader> groupMap = new HashMap<String, ArticleHeader>();

	class FileInfo {
		public String filename;
		public int encoding;
		public String boundary;
	}

	private static int progressId = 0;
	private static HashMap<String, Progress> progressById = new HashMap<String, Progress>();
	
	private static int shortId = 0;
	private static HashMap<String, String> articleIdByShortId = new HashMap<String, String>();
	
	private static final int CODE_NONE = 0;
	private static final int CODE_BASE64 = 1;
	private static final int CODE_UU = 2;
	private static final int CODE_YENC = 3;

	private static int chunkSize = 20000;
	
	private static ReaderCache readerCache;
	private static BodyCache bodyCache;
	
	private static class User {
		public String username;
		public String password;
		public String host;
	}
	
	private static Map<String, User> users = new HashMap<String, User>();
	
	public NewsService() {
		try {
			System.setProperty("file.encoding","UTF-8");
			Field charset;
			charset = Charset.class.getDeclaredField("defaultCharset");
			charset.setAccessible(true);
			charset.set(null,null);
		} catch (Exception e) {
		}
		
		bodyCache = new BodyCache();
		//readerCache = new ReaderCache();
	}

	@POST
	@Produces(MediaType.TEXT_PLAIN)
	public String getInfo() {
		return "Usenet News (NNTP) REST Service";
	}

	@POST
	@Path("l")
	@Produces(MediaType.TEXT_PLAIN)
	public String login(@FormParam("host") String host)
			throws SocketException, IOException {
		connect(host);
		String token = String.valueOf(System.currentTimeMillis());
		User user = parseUser(host);
		users.put(token, user);
		return token;
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

		for (int i = 0; i < infos.length /* && i < max*/; i++) {
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
			@FormParam("offset") int offset, @FormParam("timeout") int timeout)
			throws SocketException, IOException {

		filter = filter.trim().toLowerCase();

		NNTPClient client = connect(host);

		NewsgroupInfo info = new NewsgroupInfo();

		client.selectNewsgroup(newsgroup, info);

		if (offset == 0) {
			multipartMap.clear();
			multivolumeMap.clear();
			groupMap.clear();
		}

		long first = info.getFirstArticleLong();
		long last = info.getLastArticleLong() - offset;

		int blockSize = 5000;

		long high = last;
		long low = Math.max(high - blockSize + 1, first);

		ArticleList list = new ArticleList();
		int start = 0;

		long startTime = System.currentTimeMillis();

		while (true) {
			BufferedReader reader = client.retrieveArticleInfo(low, high);

			String line;
			while ((line = reader.readLine()) != null) {
				if (list.articles.size() < count) {
					String[] s = line.split("\t");
					String subject = s[1];
					String id = s[4];
					int bytes = s[6].length() > 0 ? Integer.parseInt(s[6]) : 0;
					ArticleHeader header = new ArticleHeader(cleanString(subject), id);
					header.bytes = bytes;

					if (!computeMultipart(header, multipartMap)
							&& header.parts != "incomplete"
							&& !isHidden(subject, filter))
						addArticle(list.articles, header, start);

					offset++;
				}
			}

			computeMultivolumes(list.articles);

			if (list.articles.size() >= count || low <= first)
				break;

			// timeout?
			if (System.currentTimeMillis() > startTime + timeout * 1000){
				list.timedout = timeout;
				break;
			}

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
	@Produces("multipart/mixed")
	public OutMultiPart getBody(@FormParam("host") String host,
			@FormParam("articleId") String articleId,
			@FormParam("screenSize") int screenSize) throws SocketException,
			IOException, ParserConfigurationException, SAXException, RarException {
		return createMultiPart(getBody(host, articleId, null, screenSize));
	}

	private static class ProgressByteArrayOutputStream extends
			ByteArrayOutputStream {
		private ByteArrayOutputStream chunk = new ByteArrayOutputStream();

		boolean cancelled;
		boolean noChunks;
		private int maxChunkSize = chunkSize;
		private long lastTime = System.currentTimeMillis();

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

		public synchronized void write(byte[] bytes, int off, int len) {
			if (!cancelled) {
				super.write(bytes, off, len);
				if (!noChunks) {
					chunk.write(bytes, off, len);
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
	public static class Progress implements Cloneable {
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
		@XmlTransient
		public byte[] chunk;
		@XmlAttribute
		public boolean hasChunk;
		@XmlAttribute
		public String filename;
		@XmlAttribute
		public String message;
		@XmlElement
		public ArticleBody body;
		@XmlElement
		public ArrayList<Integer> attSizes;
		@XmlAttribute
		public String resetMessage;

		boolean cancelled;
		boolean updated;
		byte[] thumbnailData;
		int thumbnailSize;

		private ProgressByteArrayOutputStream buffer;
		
		private boolean downloading;
		
		public void reset(String resetMessage) {
			complete = false;
			bytesRead = 0;
			part = 0;
			partCount = 0;
			exception = null;
			chunk = null;
			hasChunk = false;
			filename = null;
			message = null;
			body = null;
			attSizes = null;
			cancelled = false;
			updated = false;
			thumbnailData = null;
			thumbnailSize = 0;
			buffer = null;
			downloading = false;
			this.resetMessage = resetMessage;
		}

		public ByteArrayOutputStream beginDecode(String filename, ArticleBody body, int multi) {
			if (buffer == null)
				buffer = new ProgressByteArrayOutputStream();
			else
				buffer.reset();
			if (thumbnailSize > 0 || (!downloading && RAR && isRAR(filename))) {
				buffer.noChunks = true;
			}
			if (filename != null)
				this.filename = filename;
			if (multi > 0) {
				// multiple attachments
				attSizes = new ArrayList<Integer>();
				for (int i = 0; i < multi; i++) {
					attSizes.add(body.attachments.get(i).data.length);
				}
			}
			return buffer;
		}
		
		public synchronized void update(int bytesRead) {
			this.bytesRead += bytesRead;
			message = null;
			updated = true;
			notify();
		}
		
		public synchronized void complete() {
			complete = true;
			message = null;
			updated = true;
			notify();
		}
		
		public synchronized void message(String message) {
			this.message = message;
			updated = true;
			notify();
		}
		
		public void getProgress() throws InterruptedException {
			// estimate connection speed and adjust max chunk size if necessary:
			if (buffer != null) {
				if (cancelled) {
					buffer.cancel();
					return;
				}
				synchronized (buffer) {
					if (filename != null && !buffer.noChunks) {
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

		public void endDecode(Attachment att, int part, ArticleBody[] bodies) {
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
		
		protected Progress copy () throws CloneNotSupportedException {
			return (Progress)super.clone();
		}
	}

	private static class ProgressReader extends BufferedReader {

		private Progress progress;
		private int sepLen = System.lineSeparator().length();

		public ProgressReader(BufferedReader reader, Progress progress) {
			super(reader);
			this.progress = progress;
		}

		public String readLine() throws IOException {
			String line = super.readLine();
			if (line != null) {
				progress.update(line.length() + sepLen);
			}
			return line;
		}
	}

	public ArticleBody getBody(final String host, String articleId,
			Progress progress, int screenSize) throws SocketException,
			IOException, ParserConfigurationException, SAXException, RarException {

		if(articleId == null){
			throw new IOException("No article id");
		}
		
		ArticleBody body;
		
		if(bodyCache != null){
			body = bodyCache.get(articleId);
			if(body != null){
				if (progress != null) {
					sendBody(body, progress, true);
				}
				return body;
			}
		}

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

			
			BufferedReader reader = null;
			
			if(readerCache != null){
				reader = readerCache.get(aid);
			}
			
			if(reader == null){
				reader = (BufferedReader) client.retrieveArticle(aid);
	
				if (reader == null)
					throw new IOException(client.getReplyString());
	
				if(readerCache != null){
					reader = readerCache.put(aid, reader);
				}
			}
			
			if (progress != null) {
				reader = new ProgressReader(reader, progress);
			}

			ArticleBody part = new ArticleBody();

			try {
				readHeaders(reader, part, fileInfo);
				readBody(reader, part, i, bodies, fileInfo);
			} catch(IOException ioex) {
				reader.close();
				reader = null;
				readerCache.remove(aid);
				throw ioex;
			} finally {
				if(reader != null){
					reader.close();
				}
			}
			
			bodies[i] = part;
		}

		disconnect(host, client);

		if (aids.length == 1) {
			body = bodies[0];
		} else {
			body = new ArticleBody();
			body.text = "";
			for (int i = 0; i < aids.length; i++) {
				ArticleBody b = bodies[i];
				if (body.from == null)
					body.from = b.from;
				if (body.date == null)
					body.date = b.date;
				if (body.newsgroups == null)
					body.newsgroups = b.newsgroups;
				body.text += b.text;
				body.size += b.size;
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

		if (screenSize > 0) {
			for (int i = 0; i < body.attachments.size(); i++) {
				Attachment a = body.attachments.get(i);
				if (isImage(a.filename)) {
					a.data = createThumbnail(a.data, screenSize);
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
						progress.message("Parsing NZB index...");
					}
					body.articles = new ArrayList<ArticleHeader>();
					parseNzb(new ByteArrayInputStream(a.data), "",
							body.articles);
				} else if (!(progress != null && progress.downloading) && RAR && isRAR(a.filename)) {
					if (progress != null) {
						progress.message("Unpacking RAR archive...");
					}
					unRAR(body);
					if (progress != null) {
						progress.reset("attachment");
						sendBody(body, progress, false);
					}
				}
			}
		}

		if(bodyCache != null){
			bodyCache.put(articleId, body);
		}
		
		return body;
	}

	private void sendBody(ArticleBody body, Progress progress, boolean fromCache) {
		// let the client display some progress
		ArrayList<Attachment> atts = body.attachments;
		if(atts != null){
			if (!fromCache) {
				progress.partCount = atts.size();
			}
			for(int i = 0; i < atts.size(); i++){
				if (!fromCache) {
					progress.part = i + 1;
				}
				Attachment att = atts.get(i);
				byte[] data = att.data;
				if(data != null && data.length > 0){
					ByteArrayOutputStream bytes = progress.beginDecode(att.filename, body, i);
					int size = chunkSize;
					for(int off = 0; off < data.length; off += size){
						int n = Math.min(size, data.length-off);
						bytes.write(data, off, n);
						if(body.bytes > 0){
							n = (int)(n * body.bytes / data.length);
						}
						progress.update(n);
					}
					bytes.toByteArray();
					bytes.reset();
					progress.endDecode(att, 1, new ArticleBody[] { body });
				}
			}
		}
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
					body = getBody(host, articleId, progress, 0);
					progress.body = progress.chunk != null ? body.cloneWithoutData() : body;
				} catch (Throwable ex) {
					progress.exception = ex.getMessage();
				}
				progress.complete();
			}
		}.start();

		return id;
	}

	@POST
	@Path("p")
	@Produces("multipart/mixed")
	public OutMultiPart getProgress(@FormParam("id") final String id)
			throws SocketException, IOException, InterruptedException, CloneNotSupportedException {
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
				return createMultiPart(progress.copy());
			}
		}
		return null;
	}

	private OutMultiPart createMultiPart(Object obj) {
		BufferedOutMultiPart multi = new BufferedOutMultiPart();
		OutPart part;
		part = new OutPart();
		part.setContentType(MediaType.APPLICATION_JSON);
		part.setBody(obj);
		multi.addPart(part);
		ArticleBody body = null;
		if (obj instanceof Progress) {
			Progress progress = (Progress) obj;
			progress.hasChunk = progress.chunk != null;
			if (progress.hasChunk) {
				part = new OutPart();
				part.setContentType(MediaType.APPLICATION_OCTET_STREAM);
				part.setBody(new ByteArrayInputStream(progress.chunk));
				multi.addPart(part);
			}
			body = progress.body;
		} else if (obj instanceof ArticleBody) {
			body = (ArticleBody) obj;
		}
		if (body != null && body.attachments != null) {
			for (int i = 0; i < body.attachments.size(); i++) {
				Attachment att = body.attachments.get(i);
				if (att.data != null) {
					part = new OutPart();
					part.setContentType(MediaType.APPLICATION_OCTET_STREAM);
					part.setBody(new ByteArrayInputStream(att.data));
					multi.addPart(part);
				}
			}
		}

		return multi;
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

	private User parseUser(String host) {
		User user = null;
		int userIndex = host.lastIndexOf('@');
		if (userIndex >= 0) {
			user = new User();
			user.username = host.substring(0, userIndex);
			int passIndex = user.username.indexOf(':');
			if (passIndex >= 0) {
				user.password = user.username.substring(passIndex + 1);
				user.username = user.username.substring(0, passIndex);
			}
			user.host = host.substring(userIndex + 1);
		}
		return user;
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

		User user = parseUser(host);
		if(user == null){
			user = users.get(host);
			if(user == null){
				throw new IOException("502 User logged out: retry to login again");
			}
		}
		
		host = user.host;
		
		int port = NNTPClient.DEFAULT_PORT;

		int portIndex = host.lastIndexOf(':');
		if (portIndex >= 0) {
			String portString = host.substring(portIndex + 1);
			if (portString.matches("\\d*")) {
				port = Integer.valueOf(portString);
				host = host.substring(0, portIndex);
			}
		}
		
		InetAddress addr = InetAddress.getByName(host);

		NNTPClient client = new NNTPClient();

		client.addProtocolCommandListener(this);

		client.connect(addr, port);

		if (user.username != null)
			client.authenticate(user.username, user.password);

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

	private FileInfo readHeaders(BufferedReader reader, ArticleBody body,
			FileInfo fileInfo) throws IOException {
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
											fileInfo.filename = cleanString(trimQuotes(p[1]));
										else if (p[0].equals("boundary")
												&& ct[0].equals("multipart/mixed"))
											fileInfo.boundary = "--"
													+ trimQuotes(p[1]);
									}
								}
							}
						} else if (h.equals("from")) {
							body.from = s[1];
						} else if (h.equals("date")) {
							body.date = s[1];
						} else if (h.equals("newsgroups")) {
							body.newsgroups = s[1];
						} else if (h.equals("bytes")) {
							try {
								body.bytes = Long.parseLong(s[1]);
							} catch(NumberFormatException ex){}
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
	
	private String cleanString(String s) {
		//return s.replaceAll("\\P{Print}", "");
		return s;
	}

	private void readBody(BufferedReader reader, ArticleBody body, int part,
			ArticleBody[] bodies, FileInfo fileInfo) throws IOException {

		StringBuffer text = new StringBuffer();

		if (fileInfo.encoding != CODE_NONE)
			decode(reader, body, part, bodies, fileInfo);

		String line;
		while ((line = reader.readLine()) != null) {

			if (fileInfo.boundary != null && line.startsWith("--")) {
				if (line.equals(fileInfo.boundary)) {
					fileInfo.encoding = CODE_NONE;
					fileInfo.filename = null;
					readHeaders(reader, body, fileInfo);
					if (fileInfo.encoding != CODE_NONE)
						decode(reader, body, part, bodies, fileInfo);
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
					fileInfo.filename = cleanString(line.substring(nameIndex + 5));
				else
					fileInfo.filename = "(unnamed)";
			} else if (line.startsWith("begin ")) {
				fileInfo.encoding = CODE_UU;
				int nameIndex = line.lastIndexOf(" ");
				if (nameIndex > 0)
					fileInfo.filename = cleanString(line.substring(nameIndex + 1));
				else
					fileInfo.filename = "(unnamed)";
			}

			if (fileInfo.encoding != CODE_NONE) {
				decode(reader, body, part, bodies, fileInfo);
			} else {
				text.append(line);
				text.append('\n');
			}
		}

		body.text = text.toString();
		body.size += text.length();
	}

	private void decode(BufferedReader reader, ArticleBody body,
			int part, ArticleBody[] bodies, FileInfo fileInfo)
			throws IOException {

		ByteArrayOutputStream bytes;
		if (reader instanceof ProgressReader) {
			bytes = ((ProgressReader) reader).progress.beginDecode(fileInfo.filename, body, body.attachments.size());
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
				((ProgressReader) reader).progress.endDecode(att, part, bodies);
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

		HeaderGroupMatchResult r = HeaderGroupMatcher.matchMultipart(article.subject);
		if (r != null) {
			ArticleHeader[] parts = map.get(r.getKey());
			if (parts == null) {
				parts = new ArticleHeader[r.getPartCount()];
				map.put(r.getKey(), parts);
			}
			parts[r.getPartNumber() - 1] = article;

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

			return r.getPartNumber() > 1;
		}

		return false;
	}

	private void computeMultivolumes(List<ArticleHeader> list) {

		for (int i = 0; i < list.size(); i++) {

			ArticleHeader article = list.get(i);

			if (article.group != null) {
				for (int j = 0; j < article.group.size(); j++) {
					ArticleHeader a = article.group.get(j);
					int nn = computeMultivolumes(a);
					if (nn > 1) {
						article.group.remove(j--);
					}
				}
				if (article.group.size() == 1) {
					list.set(i, article.group.get(0));
				}
			} else {
				int n = computeMultivolumes(article);
				if (n > 1) {
					list.remove(i--);
				}
			}
		}
		for (int i = 0; i < list.size(); i++) {
			ArticleHeader article = list.get(i);
			if(article.mvbytes > 0){
				article.bytes = article.mvbytes;
			}
		}
	}

	private int computeMultivolumes(ArticleHeader article) {
		HeaderGroupMatchResult m = HeaderGroupMatcher.matchMultivolume(article.subject);
		
		if (m != null) {
			Map<String, ArticleHeader[]> map = multivolumeMap;

			ArticleHeader[] parts = map.get(m.getKey());
			if (parts == null) {
				parts = new ArticleHeader[m.getPartCount()];
				map.put(m.getKey(), parts);
			}
			parts[m.getPartNumber() - 1] = article;

			if (parts[0] != null) {
				parts[0].vols = "";
				parts[0].mvbytes = parts[0].bytes;
				for (int i = 0; i < parts.length; i++) {
					if (parts[i] == null) {
						break;
					}
					if (i > 0) {
						parts[0].vols += ",";
						parts[0].mvbytes += parts[i].bytes;
					}
					parts[0].vols += parts[i].parts != null ? parts[i].parts : parts[i].articleId;
				}
			}

			return m.getPartNumber();
		}
		
		return 0;
	}
	
	private void addArticle(List<ArticleHeader> list, ArticleHeader header,
			int start) {
		String key = header.subject.replaceAll("\\d+", "");
		if(header.newsgroups != null){
			key += header.newsgroups;
		}
		ArticleHeader last = groupMap.get(key);
		if (last != null) {
			ArticleHeader group;
			if (last.group == null) {
				// create group header
				group = new ArticleHeader();
				group.group = new ArrayList<ArticleHeader>();
				group.key = key;
				group.group.add(last);
				group.subject = last.subject;
				groupMap.put(key, group);
				int index = list.indexOf(last);
				if (index >= 0) {
					list.set(index, group);
				}
			} else {
				// add to existing group header
				group = last;
			}
			if (!group.group.contains(header)) {
				group.group.add(header);
				Collections.sort(group.group);
				if (header.subject.compareTo(group.subject) < 0) {
					group.subject = header.subject;
				}
			}
			if (list.indexOf(group) >= 0)
				return;
			header = group;
		} else {
			groupMap.put(key, header);
		}
		if (start >= 0) {
			list.add(start, header);
		} else {
			list.add(header);
		}
	}

	// ---------------
	// Search
	// ---------------

	@POST
	@Path("s")
	@Produces(MediaType.APPLICATION_JSON)
	public ArticleList getNzb(@FormParam("engine") String engine,
			@FormParam("pattern") String pattern,
			@FormParam("filter") String filter, @FormParam("max") int max,
			@FormParam("age") int age, @FormParam("offset") int offset)
			throws MalformedURLException, IOException,
			ParserConfigurationException, SAXException {

		filter = filter.trim().toLowerCase();

		ArticleList list = new ArticleList();

		SearchEngine searchEngine = SearchEngine.get(engine);
		SearchEngine.Result result = searchEngine.search(pattern, filter, max,
				age, offset);

		if (offset == 0) {
			multipartMap.clear();
			multivolumeMap.clear();
			groupMap.clear();
		}

		if (result != null) {
			parseNzb(result.getNzb(), filter, list.articles);
			list.available = result.isMoreAvailable() ? max : 0;
		}

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
			ArticleHeader article = new ArticleHeader(cleanString(subject), null);
			// get newsgroups to separate different posts with same files
			NodeList groups = file.getElementsByTagName("groups");
			if (groups.getLength() > 0) {
				groups = ((Element) groups.item(0))
						.getElementsByTagName("group");
				if (groups.getLength() > 0) {
					String newsgroups = "";
					for (int j = 0; j < groups.getLength(); j++) {
						String group = groups.item(j).getTextContent();
						newsgroups += "," + group;
					}
					article.newsgroups = newsgroups;
				}
			}
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
						if (number >= 0){
							if(number >= ids.length){
								number = 0;
							}
							while(ids[number] != null && number < ids.length-1){
								number++;
							}
							ids[number] = segment.getTextContent();
						}
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
			addArticle(articles, article, -1);
		}

		computeMultivolumes(articles);

		in.close();
	}

	// ------------------------------
	// UnRAR
	// ------------------------------

	private void unRAR(ArticleBody body) throws IOException, RarException {
		Attachment a = body.attachments.get(0);
		
		String rarDirPath = Utils.getDataDir() + File.separator + "rar";
		Files.createDirectories(Paths.get(rarDirPath));
		
		String rarFilePath = rarDirPath + File.separator + System.currentTimeMillis() + ".rar";
		Files.write(Paths.get(rarFilePath), a.data);
		
		File rarFile = new File(rarFilePath);
		
		Archive archive = new Archive(new NativeStorage(rarFile));

		body.attachments.clear();

		try {
			for(FileHeader fileHeader: archive.getFileHeaders()){
				if(!fileHeader.isDirectory()){
					ByteArrayOutputStream out = new ByteArrayOutputStream();
					archive.extractFile(fileHeader, out);
					byte[] fileData = out.toByteArray();
					String fileName = fileHeader.getFileNameString().replaceAll("[\\\\\\/]", "_");
					body.attachments.add(new Attachment(fileName, fileData));
				}
			}
		} finally {
			archive.close();
			rarFile.delete();
		}
		
		if(body.attachments.size() == 1 && isRAR(body.attachments.get(0).filename)){
			unRAR(body);
		}
	}
	
	// ------------------------------
	// Index thumbnails
	// ------------------------------

	@POST
	@Path("th")
	@Produces("multipart/mixed")
	public OutMultiPart getThumbnail(@FormParam("host") String host,
			@FormParam("articleId") String articleId,
			@FormParam("size") int size) throws SocketException, IOException,
			ParserConfigurationException, SAXException, RarException {
		return createMultiPart(getThumbnail(host, articleId, size, null));
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
				progress.complete();
			}
		}.start();

		return id;
	}

	private ArticleBody getThumbnail(String host, String articleId, int size,
			Progress progress) throws SocketException, IOException,
			ParserConfigurationException, SAXException, RarException {

		ArticleBody body = getBody(host, articleId, progress, 0);

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
	// Direct attachment download
	// ---------

	@POST
	@Path("i")
	@Produces(MediaType.TEXT_PLAIN)
	public String getShortId(@FormParam("articleId") final String articleId) {
		String id = String.valueOf(++shortId);
		articleIdByShortId.put(id, articleId);
		return id;
	}
	
	@GET
	@Path("a/{host}/{id}/{name}")
	public Response getAttachment(@PathParam("host") final String host,
			@PathParam("id") String id,
			@PathParam("name") final String name) throws SocketException,
			IOException, ParserConfigurationException, SAXException {
		
		final Progress progress = new Progress();
		progress.downloading = true;
		
		String longId = articleIdByShortId.get(id);
		final String articleId =  longId != null ? longId : id;
		new Thread() {
			public void run() {
				try {
					getBody(host, articleId, progress, 0);
				} catch (Throwable ex) {
					progress.exception = ex.getMessage();
				}
				progress.complete();
			}
		}.start();
		
		StreamingOutput streamingOutput = new StreamingOutput() {
			public void write(OutputStream out) throws IOException {
				while (true) {
					synchronized (progress) {
						while (!progress.updated) {
							try {
								progress.wait();
							} catch (InterruptedException ex) {
								ex.printStackTrace();
							}
						}
						try {
							progress.getProgress();
							if(name.equals(progress.filename)){
								out.write(progress.chunk);
								out.flush();
							}
						} catch (Exception ex) {
							ex.printStackTrace();
							progress.cancelled = true;
							break;
						}
						if (progress.complete) {
							break;
						}
						progress.updated = false;
					}
				}
				try {
					out.flush();
				} catch (IOException ex) {
					ex.printStackTrace();
				}
			}
		};
		
		int dot = name.lastIndexOf('.');
		String suffix = dot >= 0 ? name.substring(dot+1) : name;
		suffix = suffix.toLowerCase();
		String contentType;
		if(suffix.equals("jpg")||suffix.equals("jpeg")||suffix.equals("gif")||suffix.equals("png")){
			contentType = "image/" + suffix;
		} else if(suffix.equals("mpg")||suffix.equals("mp4")||suffix.equals("avi")||suffix.equals("ogg")||suffix.equals("ogv")||suffix.equals("mov")) {
			contentType = "video/" + suffix;
		} else {
			contentType = "application/octet-stream";
		}
		
		ResponseBuilder b = Response.ok(streamingOutput);
		b.header("Content-Type", contentType);
		return b.build();
	}

	// ---------
	// Cache
	// ---------

	@POST
	@Path("ci")
	@Produces(MediaType.APPLICATION_JSON)
	public CacheInfo getCacheInfo(){
		if(bodyCache != null){
			return bodyCache.getInfo();
		} else if(readerCache != null){
			return readerCache.getInfo();
		} else {
			return new CacheInfo();
		}
	}
	
	@POST
	@Path("cc")
	@Produces(MediaType.APPLICATION_JSON)
	public CacheInfo clearCache(){
		if(bodyCache != null){
			bodyCache.clear();
			return bodyCache.getInfo();
		} else if(readerCache != null){
			readerCache.clear();
			return readerCache.getInfo();
		} else {
			return new CacheInfo();
		}
	}
	
	// ---------
	// Utilities
	// ---------

	private static boolean isImage(String filename) {
		String name = filename.toLowerCase();
		return name.endsWith(".jpg") || name.endsWith(".gif")
				|| name.endsWith(".png");
	}

	private static boolean isRAR(String filename) {
		String name = filename.toLowerCase();
		return name.endsWith(".rar") || name.endsWith(".r00") || name.endsWith(".cbr");
	}

	private boolean isHidden(String subject, String filter) {
		if (!(filter.isEmpty() || subject.toLowerCase().contains(filter))) {
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
