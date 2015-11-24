package news;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

@XmlRootElement
public class Attachment {
	
	@XmlAttribute public String filename;
	@XmlTransient public byte[] data;
	
	public Attachment() {}
	
	public Attachment(String filename, byte[] data) {
		this.filename = filename;
		this.data = data;
	}
}
