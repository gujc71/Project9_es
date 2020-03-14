package gu.search;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.http.HttpHost;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;

import gu.board.BoardReplyVO;
import gu.board.BoardSvc;
import gu.board.BoardVO;
import gu.common.Field3VO;
import gu.common.FileUtil;
import gu.common.FileVO;
import gu.common.LocaleMessage;

@Controller
@EnableAsync
@EnableScheduling
public class IndexingCtr {

    @Autowired
    private BoardSvc boardSvc;
    
    static final Logger logger = LoggerFactory.getLogger(IndexingCtr.class);
    static final String INDEX_NAME = "project9";
    static final String LAST_FILE = "D:\\project9.last";
    static final String FILE_EXTENTION = "doc,ppt,xls,docx,pptx,xlsx,pdf,txt,zip,hwp";
    static boolean is_indexing = false;
    private Properties lastFileProps = null;            // 마지막 색인값 보관
    private String file_path = null; 					// 첨부 파일 경로

    /*
     * 색인
     * 1. 게시판
     * 2. 댓글
     * 3. 첨부파일 
     */
    @Scheduled(cron="0 */1 * * * ?")
    public void indexingFile() {
    	if (is_indexing) return;
    	is_indexing = true;
    	loadLastValue();
    	file_path = LocaleMessage.getMessage("info.filePath") + "/";  //  첨부 파일 경로
        // ---------------------------- 게시판  --------------------------------
        RestHighLevelClient client = createConnection();

        String brdno = getLastValue("brd");        		// 이전 색인시 마지막 일자
        
        List<BoardVO> boardlist = (List<BoardVO>) boardSvc.selectBoards4Indexing(brdno);
        for (BoardVO el : boardlist) {
        	brdno = el.getBrdno();
        	IndexRequest indexRequest = new IndexRequest(INDEX_NAME)
        		    .id(el.getBrdno())
        		    .source("bgno", el.getBgno(),
        		    		"brdno", brdno,
        		    		"brdtitle", el.getBrdtitle(),
        		    		"brdmemo", el.getBrdmemo(),
        		    		"brdwriter", el.getUsernm(),
        		    		"userno", el.getUserno(),
        		    		"brddate", el.getBrddate(),
        		    		"brdtime", el.getBrdtime()
        		    		); 

            try {
                client.index(indexRequest, RequestOptions.DEFAULT);
            } catch (IOException | ElasticsearchStatusException e) {
            	logger.error("indexRequest : " + e);
            }
        }
        
        if (boardlist.size()>0) {
            writeLastValue("brd", brdno); // 마지막 색인 이후의 댓글/ 첨부파일 중에서 게시글이 색인 된 것만 색인 해야 함. SQL문에서 field1참조  => logtash를 쓰지 않고 개발한 이유
        }
        
        
        logger.info("board indexed : " + boardlist.size());
        boardlist.clear();
        boardlist = null;
        
        // ---------------------------- 댓글 --------------------------------
        Field3VO lastVO = new Field3VO(); // 게시판, 댓글, 파일의 마지막 색인 값
        lastVO.setField1(brdno);
        lastVO.setField2(getLastValue("reply"));
        
    	List<BoardReplyVO> replylist = (List<BoardReplyVO>) boardSvc.selectBoardReply4Indexing(lastVO);

    	String reno = "";
        for (BoardReplyVO el : replylist) {
        	reno = el.getReno();
            Map<String, Object> replyMap = new HashMap<String, Object>();
            replyMap.put("reno", reno);
            replyMap.put("redate", el.getRedate());
            replyMap.put("rememo", el.getRememo());
            replyMap.put("usernm", el.getUsernm());
            replyMap.put("userno", el.getUserno());

            Map<String, Object> singletonMap = Collections.singletonMap("reply", replyMap);

        	UpdateRequest updateRequest = new UpdateRequest()
        	    .index(INDEX_NAME)
        	    .id(el.getBrdno())
        	    .script(new Script(ScriptType.INLINE, "painless", "if (ctx._source.brdreply == null) {ctx._source.brdreply=[]} ctx._source.brdreply.add(params.reply)", singletonMap));

            try {
                client.update(updateRequest, RequestOptions.DEFAULT);
            } catch (IOException | ElasticsearchStatusException e) {
            	logger.error("updateCommit : " + e);
            }
        }
        
        if (replylist.size()>0) {
            writeLastValue("reply", reno); // 마지막 색인  정보 저장 - 댓글
        }
        
        logger.info("board reply indexed : " + replylist.size());
        replylist.clear();
        replylist = null;

        // ---------------------------- 첨부파일 --------------------------------
        lastVO.setField2(getLastValue("file"));
    	List<FileVO> filelist = (List<FileVO>) boardSvc.selectBoardFiles4Indexing(lastVO);

    	String fileno = "";
        for (FileVO el : filelist) {
        	if (!FILE_EXTENTION.contains(FileUtil.getFileExtension(el.getFilename()))) continue; // 지정된 파일 형식만 색인
        	
        	fileno = el.getFileno().toString();
            Map<String, Object> fileMap = new HashMap<String, Object>();
            fileMap.put("fileno", fileno);
            fileMap.put("filememo", extractTextFromFile(el.getRealname()) );	
            
            Map<String, Object> singletonMap = Collections.singletonMap("file", fileMap);
            
        	UpdateRequest updateRequest = new UpdateRequest()
        	    .index(INDEX_NAME)
        	    .id(el.getParentPK())
        	    .script(new Script(ScriptType.INLINE, "painless", "if (ctx._source.brdfiles == null) {ctx._source.brdfiles=[]} ctx._source.brdfiles.add(params.file)", singletonMap));
            try {
                client.update(updateRequest, RequestOptions.DEFAULT);
            } catch (IOException | ElasticsearchStatusException e) {
            	logger.error("updateCommit : " + e);
            }
        }
        if (filelist.size() > 0) {
            writeLastValue("file", fileno); // 마지막 색인  정보 저장 - 댓글
        }
        
        logger.info("board files indexed : " + filelist.size());
        filelist.clear();
        filelist = null;
        
        try {
			client.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
        is_indexing = false;
    }
    
    /*
     * tika로 오피스 등에서 텍스트 추출
     */   
    private String extractTextFromFile(String filename) {

        File file = new File(file_path + filename.substring(0,4) + "/" + filename);
        if (!file.exists()) {
        	logger.error("file not exists : " + filename);
        	return "";
        }
        String text = "";
        Tika tika = new Tika();
    	try {
    		text = tika.parseToString(file);			// binary contents => text contents
		} catch (IOException | TikaException e) {
			e.printStackTrace();
		}    
    	return text;
    }
    // ---------------------------------------------------------------------------
    /*
     * 처리한 마지막 값(날짜)을 저장한 파일 열고 읽기
     */    
    private void loadLastValue() {
        lastFileProps = new Properties();
        try {
            lastFileProps.load(new FileInputStream( LAST_FILE ));
        } catch (IOException e) {
            logger.error("" + e);
        }
    }
    /*
     * 처리한 마지막 값(날짜) 쓰기
     */    
    private void writeLastValue(String key, String value) {
    	lastFileProps.setProperty(key, value);    // 마지막 색인 일자 저장

        try {
            FileOutputStream lastFileOut = new FileOutputStream(LAST_FILE);
            lastFileProps.store(lastFileOut,"Last Indexing");
            lastFileOut.close();
        } catch (IOException e) {
        	logger.error("writeLastValue : " + e);
        }
    }
    /*
     * 데이터 종류별 마지막 값 반환
     */    
    private String getLastValue(String key) {
    	String value = lastFileProps.getProperty(key);
    	if (value==null) value="0";
    	return value;
    }
    // ---------------------------------------------------------------------------
    public RestHighLevelClient createConnection() {
        return new RestHighLevelClient(
                    RestClient.builder(
                            new HttpHost("localhost", 9200, "http")
                    )
                );
    }
    // ---------------------------------------------------------------------------
}
