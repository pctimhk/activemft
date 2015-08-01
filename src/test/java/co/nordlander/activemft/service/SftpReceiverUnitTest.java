package co.nordlander.activemft.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.inject.Inject;
import javax.jms.JMSException;
import javax.jms.Message;

import org.apache.activemq.util.ByteArrayOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import co.nordlander.activemft.config.BrokerConfig;
import co.nordlander.activemft.domain.TransferEvent;
import co.nordlander.activemft.domain.TransferJob;
import co.nordlander.activemft.repository.TransferEventRepository;
import co.nordlander.activemft.service.util.Constants;

/**
 * Testing SftpReceiver a single module.
 * Actually an integration test as it starts a SFTP server and a JMS broker.
 * @author petter
 * 
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = BrokerConfig.class)
public class SftpReceiverUnitTest {

	private static final Logger log = LoggerFactory.getLogger(SftpReceiverUnitTest.class);
	protected SftpReceiver sftpReceiver;
	
	@ClassRule public static TemporaryFolder temporaryFolder = new TemporaryFolder();
	
	@Inject JmsTemplate jmsTemplate;
	
	static protected EmbeddedSftp sftpServer;

	static File sftpRootFolder; // Physical path to SFTP root folder
	protected File sourceFolder; // Physical path to SFTP sub-folder
	
	static protected String sftpUsername = "sftpuser";
	static protected String sftpPassword = "sftpPassword";
	
	// Encoding trip-wire: "I can eat glass and it doesn't hurt me." - in old norse. 
	protected static final String SAMPLE_TEXT = "ᛖᚴ ᚷᛖᛏ ᛖᛏᛁ ᚧ ᚷᛚᛖᚱ ᛘᚾ ᚦᛖᛋᛋ ᚨᚧ ᚡᛖ ᚱᚧᚨ ᛋᚨᚱ";
	
	protected TransferEventRepository mockedEventRepo;
	
	@BeforeClass
	public static void setupSftp() throws IOException{
		sftpRootFolder = temporaryFolder.newFolder("sftproot");
		sftpServer = new EmbeddedSftp(0, sftpUsername, sftpPassword, sftpRootFolder);
	}
	
	@AfterClass
	public static void tearDownSftp(){
		sftpServer.stop();
		sftpServer.close();
	}
	
	@Before
	public void setupTest(){
		
		sftpReceiver = new SftpReceiver();
		mockedEventRepo = mock(TransferEventRepository.class);
		
		// Make saveAndFlush return whatever is supposed to be saved to avoid NPEs.
		when(mockedEventRepo.saveAndFlush(any(TransferEvent.class))).then( new Answer<TransferEvent>(){
			public TransferEvent answer(InvocationOnMock invocation) throws Throwable {
				TransferEvent event =  invocation.getArgumentAt(0, TransferEvent.class);
				event.setId( event.getId() != null ? event.getId() : 1L);
				return event;
			}
		});
		
		sftpReceiver.eventRepo = mockedEventRepo;
		jmsTemplate.setReceiveTimeout(1000L);
		sftpReceiver.jmsTemplate = jmsTemplate;
		
		sftpReceiver.setup();
		
		sourceFolder = new File(sftpRootFolder,"files");
		sourceFolder.mkdir();
		log.info("Test setup done");
	}
	
	/**
	 * A long test that verifies a receive of two files from SFTP with filter.
	 */
	@Test
	public void testSftpReceive() throws FileNotFoundException, Exception{
		
		final File file1 = new File(sourceFolder,"file1.xml");
		final File file2 = new File(sourceFolder,"file2.xml");
		final File notIncludedFile = new File(sourceFolder,"file1.txt");
		
		FileUtils.write(file1, SAMPLE_TEXT);
		FileUtils.write(file2, SAMPLE_TEXT + SAMPLE_TEXT);
		FileUtils.write(notIncludedFile, "not important");
		
		
		final long file1Size = file1.length();
		final long file2Size = file2.length();
		
		TransferJob job = sftpJobTemplate();
		job.setSourceUrl("sftp://localhost:"+sftpServer.getPort()+"/files");
		job.setSourceFilepattern("*.xml");
		
		sftpReceiver.receiveFiles(job);
		
		Message resultMsg1 = jmsTemplate.receive("transfers");
		Message resultMsg2 = jmsTemplate.receive("transfers");
		Message shouldBeNull = jmsTemplate.receive("transfers");
		
		assertNotNull(resultMsg1);
		assertNotNull(resultMsg2);
		assertNull(shouldBeNull);

		assertEquals("test-job",resultMsg1.getStringProperty("jobName"));
		assertNotNull(resultMsg1.getStringProperty("jobId"));
		assertEquals("/files/file1.xml",resultMsg1.getStringProperty("filepath"));
		assertEquals("file1.xml",resultMsg1.getStringProperty("filename"));
		assertEquals(file1Size,resultMsg1.getLongProperty("size"));
		assertEquals(SAMPLE_TEXT,readMessageContentUTF8(resultMsg1));
		
		assertEquals("test-job",resultMsg2.getStringProperty("jobName"));
		assertNotNull(resultMsg2.getStringProperty("jobId"));
		assertEquals("/files/file2.xml",resultMsg2.getStringProperty("filepath"));
		assertEquals("file2.xml",resultMsg2.getStringProperty("filename"));
		assertEquals(file2Size,resultMsg2.getLongProperty("size"));
		assertEquals(SAMPLE_TEXT + SAMPLE_TEXT,readMessageContentUTF8(resultMsg2));
	
		assertFalse(file1.exists());
		assertFalse(file2.exists());
		assertTrue(notIncludedFile.exists());
	}
	
	/**
	 * Assure nothing is saved in event database if there are no files to transfer.
	 */
	@Test
	public void testNoFilesToReceive() throws FileNotFoundException, Exception{
		TransferJob job = sftpJobTemplate();
		job.setSourceUrl("sftp://localhost:"+sftpServer.getPort()+"/files");
		job.setSourceFilepattern("*.xml");
		
		sftpReceiver.receiveFiles(job);
		Message shouldBeNull = jmsTemplate.receive("transfers");
		assertNull(shouldBeNull);
		
		verify(mockedEventRepo,never()).saveAndFlush(any());
	}
	
	protected TransferJob sftpJobTemplate(){
		TransferJob job = new TransferJob();
		job.setEnabled(true);
		job.setId(1L);
		job.setName("test-job");
		job.setCronExpression("*/5 * * ? * *");
		job.setSourceFilepattern("*");
		job.setSourceType(Constants.SFTP_TYPE);
		job.setSourceUsername(sftpUsername);
		job.setSourcePassword(sftpPassword);
		return job;
	}
	
	/**
	 * Read Large message content from Artemis message.
	 * @param msg the message to read large message stream from 
	 * @return the large message content parsed as UTF-8 string.
	 * @throws JMSException
	 * @throws IOException
	 */
	protected String readMessageContentUTF8(Message msg) throws JMSException, IOException{
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		msg.setObjectProperty("JMS_AMQ_SaveStream", bos);
		return IOUtils.toString(bos.toByteArray(),StandardCharsets.UTF_8.name());
	}
}
