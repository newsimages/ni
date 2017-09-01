package news;

import java.util.ArrayList;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

@XmlRootElement
public class ArticleBody {
	@XmlAttribute public String from;
	@XmlAttribute public String date;
	@XmlAttribute public String newsgroups;
	@XmlAttribute public String text;
	@XmlAttribute public long bytes;
	@XmlElement public ArrayList<Attachment> attachments = new ArrayList<Attachment>();
	@XmlAttribute public int size;
	@XmlElement public ArrayList<ArticleHeader> articles;
	
	public ArticleBody cloneWithoutData(){
		ArticleBody b = new ArticleBody();
		b.text = text;
		b.from = from;
		b.date = date;
		b.newsgroups = newsgroups;
		b.size = size;
		b.bytes = bytes;
		for(int i = 0; i < attachments.size(); i++){
			Attachment att = attachments.get(i);
			Attachment a = new Attachment(att.filename, null);
			b.attachments.add(a);
		}
		b.articles = articles;
		return b;
	}
}
