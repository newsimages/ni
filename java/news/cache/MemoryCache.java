package news.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import news.ArticleBody;

public class MemoryCache {

	private int maxSize;
	
	private int currentSize = 0;
	private Map<String, ArticleBody> cache = new HashMap<String, ArticleBody>();
	private List<String> ids = new ArrayList<String>();
	
	public MemoryCache(int maxSize){
		this.maxSize = maxSize;
	}
	
	public MemoryCache(){
		this(200*1024*1024);
	}
	
	public void put(String articleId, ArticleBody body){
		//System.out.println("put " + articleId.substring(0, Math.min(articleId.length()-1, 20)) + " (" + body.size + " bytes)");
		while(currentSize + body.size > maxSize && ids.size() > 0){
			String oldestId = ids.get(0);
			//System.out.println("  removing " + oldestId.substring(0, Math.min(oldestId.length()-1, 20)) + " (" + cache.get(oldestId).size + " bytes)");
			cache.remove(oldestId);
			ids.remove(0);
		}
		if(currentSize + body.size <= maxSize){
			cache.put(articleId, body);
			ids.add(articleId);
			currentSize += body.size;
			//System.out.println("  added: new size = " + currentSize);
		}
	}
	
	public ArticleBody get(String articleId){
		ArticleBody body = cache.get(articleId);
		//System.out.println("put " + articleId.substring(0, Math.min(articleId.length()-1, 20)) + " : " + (body != null ? "found (" + body.size + " bytes)" : " not found."));
		return body;
	}
}
