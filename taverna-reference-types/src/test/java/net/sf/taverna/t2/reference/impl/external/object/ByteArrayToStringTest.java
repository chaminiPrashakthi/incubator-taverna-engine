package net.sf.taverna.t2.reference.impl.external.object;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.Charset;
import java.util.Set;

import net.sf.taverna.t2.reference.ExternalReferenceSPI;
import net.sf.taverna.t2.reference.ReferenceContext;
import net.sf.taverna.t2.reference.ReferenceSet;
import net.sf.taverna.t2.reference.impl.EmptyReferenceContext;
import net.sf.taverna.t2.reference.impl.TranslationPath;
import net.sf.taverna.t2referencetest.DummyReferenceSet;

import org.junit.Test;

public class ByteArrayToStringTest {

	protected TranslationPath path  = new TranslationPath();
	private final Charset UTF8 = Charset.forName("UTF-8");
	// cleverly including the supplementary character U+10400
	// which in UTF8 should be \xf0\x90\x90\x80
	private final String string = "Ferronni\u00e8re \ud801\udc00";	
	
	
	@Test
	public void translateStringToByteTranslator() throws Exception {
		ReferenceContext context = new EmptyReferenceContext();
		InlineStringReference inlineString = new InlineStringReference();
		inlineString.setContents(string);
		path.setSourceReference(inlineString);
		ReferenceSet rs = new DummyReferenceSet(inlineString);		
		path.getTranslators().add(new InlineStringToInlineByteTranslator());
		
		Set<ExternalReferenceSPI> set = path.doTranslation(rs, context);		
		assertEquals(1, set.size());
		InlineByteArrayReference byteRef = (InlineByteArrayReference) set.iterator().next();
		
		assertEquals(string, new String(byteRef.getValue(), UTF8));		
	}

	@Test
	public void translateByteToStringTranslator() throws Exception {
		ReferenceContext context = new EmptyReferenceContext();
		InlineByteArrayReference inlineByte = new InlineByteArrayReference();
		inlineByte.setValue(string.getBytes(UTF8));
		path.setSourceReference(inlineByte);
		ReferenceSet rs = new DummyReferenceSet(inlineByte);		
		path.getTranslators().add(new InlineByteToInlineStringTranslator());		
		
		Set<ExternalReferenceSPI> set = path.doTranslation(rs, context);		
		assertEquals(1, set.size());
		InlineStringReference inlineString = (InlineStringReference) set.iterator().next();
		assertEquals(string,  inlineString.getContents());	
	}

	
	@Test
	public void translateStringToByteArrayBuilder() throws Exception {
		ReferenceContext context = new EmptyReferenceContext();
		InlineStringReference inlineString = new InlineStringReference();
		inlineString.setContents(string);
		path.setSourceReference(inlineString);
		ReferenceSet rs = new DummyReferenceSet(inlineString);
		path.setInitialBuilder(new InlineByteArrayReferenceBuilder());
		Set<ExternalReferenceSPI> set = path.doTranslation(rs, context);		
		assertEquals(1, set.size());
		InlineByteArrayReference byteRef = (InlineByteArrayReference) set.iterator().next();
		
		assertEquals(string, new String(byteRef.getValue(), UTF8));		
	}

	@Test
	public void translateByteArrayToStringBuilder() throws Exception {
		ReferenceContext context = new EmptyReferenceContext();
		InlineByteArrayReference inlineByte = new InlineByteArrayReference();
		inlineByte.setValue(string.getBytes(UTF8));
		path.setSourceReference(inlineByte);
		ReferenceSet rs = new DummyReferenceSet(inlineByte);
		path.setInitialBuilder(new InlineStringReferenceBuilder());
		Set<ExternalReferenceSPI> set = path.doTranslation(rs, context);		
		assertEquals(1, set.size());
		assertTrue(set.iterator().next() instanceof InlineStringReference);
		InlineStringReference inlineString = (InlineStringReference) set.iterator().next();
		assertEquals(string,  inlineString.getContents());
		//System.out.println(string);
	}
	
	
}
