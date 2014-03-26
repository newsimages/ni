package news;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class Attachment {
	
	@XmlAttribute public String filename;
	@XmlAttribute public byte[] data;
	
	public Attachment() {}
	
	public Attachment(String filename, byte[] data) {
		this.filename = filename;
		this.data = data;
	}
}
