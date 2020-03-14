package gu.common;


public class FullTextSearchVO extends SearchVO  {

    private String searchRange = "";           // 검색 대상 필드 - 작성자, 제목, 내용등 
    private String searchTerm = "";            // 기간 조회 사용여부  
    private String searchTerm1 = "";           // 시작일자  
    private String searchTerm2 = "";           // 종료일자  
    private String userno = "";          		 // 사용자 권한 테스트용
    
	public String getSearchRange() {
		return searchRange;
	}
	public String getSearchTerm() {
		return searchTerm;
	}
	public String getSearchTerm1() {
		return searchTerm1;
	}
	public String getSearchTerm2() {
		return searchTerm2;
	}
	public String getUserno() {
		return userno;
	}
	public void setSearchRange(String searchRange) {
		this.searchRange = searchRange;
	}
	public void setSearchTerm(String searchTerm) {
		this.searchTerm = searchTerm;
	}
	public void setSearchTerm1(String searchTerm1) {
		this.searchTerm1 = searchTerm1;
	}
	public void setSearchTerm2(String searchTerm2) {
		this.searchTerm2 = searchTerm2;
	}
	public void setUserno(String userno) {
		this.userno = userno;
	}
    
}
 