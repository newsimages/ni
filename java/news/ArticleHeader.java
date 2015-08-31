package news;

import java.util.List;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

@XmlRootElement
public class ArticleHeader {
	@XmlAttribute public String subject;
	@XmlAttribute public String articleId;
	@XmlAttribute public String parts;
	@XmlAttribute public String vols;
	@XmlAttribute public int bytes;
	@XmlElement public List<ArticleHeader> group;
	
	public ArticleHeader(){}
	
	public ArticleHeader(String subject, String articleId) {
		this.subject = subject;
		this.articleId = articleId;
	}
}
