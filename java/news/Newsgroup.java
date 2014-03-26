package news;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class Newsgroup {
	@XmlAttribute public String name;
	@XmlAttribute public long articleCount;
	@XmlAttribute public long firstArticle;
	@XmlAttribute public long lastArticle;
	
	public Newsgroup(){}
	
	public Newsgroup(String name, long articleCount, long firstArticle,
			long lastArticle) {
		super();
		this.name = name;
		this.articleCount = articleCount;
		this.firstArticle = firstArticle;
		this.lastArticle = lastArticle;
	}
}
