import java.util.Arrays;

class Header {
	String key;
	String value;
}

public class FormMultipart extends FormParser {
	
	boolean substr_cmp(byte a[], int start, int len, byte b[])
		{
		int j = 0;
		for (int i = start; i < start+len; i++)
			{
			if (a[i] != b[j]) return false;
			j ++;
			}
		
		return true;
		}
	
	int getBoundaryOffset(byte payload[], int start, byte boundary[])
		{
		for (int i = start; i < payload.length && payload.length - i >= boundary.length; i ++)
			{
			if (substr_cmp(payload, i, boundary.length, boundary))
				return i;
			}
		
		return -1;
		}
	
	String getHeaderValue(Header hdrs[], String key)
		{
		key = key.toLowerCase();
		
		for (int i = 0; i < hdrs.length; i++)
			{
			if (hdrs[i].key.toLowerCase().equals(key))
				{
				return hdrs[i].value;
				}
			}
		
		return null;
		}
	
	boolean headerExists(Header hdrs[], String key)
		{
		key = key.toLowerCase();
		
		for (int i = 0; i < hdrs.length; i++)
			{
			if (hdrs[i].key.toLowerCase().equals(key))
				{
				return true;
				}
			}
		
		return false;
		}
	
	//TODO: this is a bit too simple for multipart headers; we need to do an explode that preserves strings using quote marks...
	Header[] getSubHeaders(String content)
		{
		String parts[] = content.split(";");
		Header headers[] = new Header[parts.length-1];
		
		for (int i = 1; i < parts.length; i++)
			{
			int ndx = parts[i].indexOf("=");
			
			String k = parts[i].substring(0, ndx).trim();
			String v = parts[i].substring(ndx+1).trim();
			
			Header nhdr = new Header();
			nhdr.key = k.toLowerCase();
			nhdr.value = v;
			
			headers[i-1] = nhdr;
			}
		
		return headers;
		}
	
	String trimQuotes(String str)
		{
		int start = 0;
		int end = str.length();
		
		if (str.charAt(0)== '\"')
			start ++;
		
		if (str.charAt(end-1) == '\"')
			end --;
		
		return str.substring(start, end);
		}
	
	boolean isEndSequence(byte b[])
		{
		if (b[0] == '\r' && b[1] == '\n' && b[2] == '\r' && b[3] == '\n')
			return true;
		
		return false;
		}
	
	FormData getFormData(String contentType, byte payload[])
		{
		FormData fdata = new FormData();
		
		//extract the boundary delimiter, which is a sub-field of the contentType header
		Header subh[] = getSubHeaders(contentType);
		
		String boundary = getHeaderValue(subh, "boundary");
		
		boundary = "--" + boundary;
		
		//from here the data is organised within payload as follows:
		// - find the first boundary field
		// - after this will be a "\r\n" (meaning a field) or a "--" (meaning the end of the fields)
		// - for a field, there are zero or more header lines, each terminated by \r\n, then a blank line with \r\n
		// - we then have the actual data of this field, up to the next boundary field, where we repeat the above
		
		int next = getBoundaryOffset(payload, 0, boundary.getBytes());
		next = next + boundary.length();
		
		boundary = "\r\n" + boundary;
		
		
		
		while (true)
			{
			//the next two bytes are either \r\n, or --
			// - in the former case, we're about to read a new section, else we're at the end
			
			if (Arrays.equals(Arrays.copyOfRange(payload, next, next+2), "\r\n".getBytes()))
				{
				//now we have header fields to read, up to a blank line
				
				int start = next;
				
				byte last4[] = new byte[4];
				
				while (! isEndSequence(last4))
					{
					byte b = payload[next];
					
					last4[0] = last4[1];
					last4[1] = last4[2];
					last4[2] = last4[3];
					last4[3] = b;
					
					next ++;
					}
				
				String buf = new String(Arrays.copyOfRange(payload, start, start + (next - start))).trim();
				
				String lines[] = buf.split("\r\n");
				Header headers[] = new Header[lines.length];
				
				for (int i = 0; i < lines.length; i++)
					{
					int ndx = lines[i].indexOf(":");
					
					String k = lines[i].substring(0, ndx).trim();
					String v = lines[i].substring(ndx+1).trim();
					
					Header nhdr = new Header();
					nhdr.key = k.toLowerCase();
					nhdr.value = v.toLowerCase();
					
					headers[i] = nhdr;
					}
				
				//now we have content, up to the start of the next boundary
				
				start = next;
				
				next = getBoundaryOffset(payload, next+1, boundary.getBytes());
				
				byte content[] = Arrays.copyOfRange(payload, start, start + (next - start));
				
				//now we've collected all headers, plus the content
				// - check which type of field this is (plain or file)
				// - we do this by checking for a field called "filename" or a content-type header
				subh = getSubHeaders(getHeaderValue(headers, "content-disposition"));
				FormField nf = null;
				
				if (headerExists(subh, "filename") || headerExists(headers, "content-type"))
					{
					FileFormField nff = new FileFormField();
					nff.name = trimQuotes(getHeaderValue(subh, "name"));
					nff.content = content;
					nff.contentType = getHeaderValue(headers, "content-type");
					nff.filename = trimQuotes(getHeaderValue(subh, "filename"));
					
					nf = nff;
					}
					else
					{
					nf = new FormField();
					nf.name = trimQuotes(getHeaderValue(subh, "name"));
					nf.content = content;
					}
				
				FormField nfields[] = null;
				if (fdata.fields == null)
					{
					nfields = new FormField[1];
					nfields[0] = nf;
					}
					else
					{
					nfields = new FormField[fdata.fields.length + 1];
					System.arraycopy(fdata.fields, 0, nfields, 0, fdata.fields.length);
					nfields[fdata.fields.length] = nf;
					}
				
				fdata.fields = nfields;
				
				next = next + boundary.length();
				}
				else if (Arrays.equals(Arrays.copyOfRange(payload, next, next+2), "--".getBytes()))
				{
				break;
				}
				else
				{
				//this is really a syntax error, but we ignore it and return whatever we have so far
				break;
				}
				
			}
		
		return fdata;
		}
	
}