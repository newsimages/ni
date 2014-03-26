package news;

import java.util.ArrayList;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

@XmlRootElement
public class ArticleBody {
	@XmlAttribute public String text;
	@XmlElement public ArrayList<Attachment> attachments = new ArrayList<Attachment>();
	@XmlTransient public int size;
	
	public ArticleBody cloneWithoutData(){
		ArticleBody b = new ArticleBody();
		b.text = text;
		b.size = size;
		b.attachments = new ArrayList<Attachment>();
		for(int i = 0; i < attachments.size(); i++){
			Attachment att = attachments.get(i);
			Attachment a = new Attachment(att.filename, null);
			b.attachments.add(a);
		}
		return b;
	}
}
