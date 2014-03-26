package news;

import java.util.LinkedHashMap;
import java.util.Map.Entry;

@SuppressWarnings("serial")
public class ArticleCache extends LinkedHashMap<String, ArticleBody> {

	private long maxSize; // in bytes
	private long size = 0; // in bytes

	public ArticleCache(long maxSize) { // in megabytes
		super(100, 0.75f, true);
		this.maxSize = maxSize * 1024 * 1024;
	}

	public ArticleCache() {
		this(100); // 100 Mb by default
	}

	protected boolean removeEldestEntry(Entry<String, ArticleBody> eldest) {
		if(size > maxSize){
			size -= eldest.getValue().size;
			return true;
		}
		return false;
	}

	public ArticleBody put(String id, ArticleBody body) {
		size += body.size;
		return super.put(id, body);
	}

	public ArticleBody remove(String id) {
		ArticleBody body = super.remove(id);
		if(body != null)
			size -= body.size;
		return body;
	}

	public void clear() {
		super.clear();
		size = 0;
	}
}
