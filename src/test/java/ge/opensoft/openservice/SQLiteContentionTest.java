package ge.opensoft.openservice;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import java.nio.file.Path;
import java.sql.*;
class SQLiteContentionTest {
 @TempDir Path root;
 @Test void publicAppearanceRemainsAvailableWithoutDatabaseOrSession() throws Exception {
  var store=mock(Store.class);when(store.open()).thenThrow(new SQLException("unavailable",null,19));var controller=new WebController(store);
  for(String path:new String[]{"/static/app.css","/static/theme.js"}){
   var request=new MockHttpServletRequest("GET",path);var response=new MockHttpServletResponse();var view=controller.dispatch(request,response);assertEquals(200,view.getStatus().value());view.getView().render(view.getModel(),request,response);assertTrue(response.getContentAsByteArray().length>100);assertNull(request.getSession(false));assertTrue(response.getContentType().startsWith(path.endsWith("css")?"text/css":"text/javascript"));
   assertEquals(200,controller.dispatch(new MockHttpServletRequest("HEAD",path),new MockHttpServletResponse()).getStatus().value());
   for(String method:new String[]{"POST","PUT"}){request=new MockHttpServletRequest(method,path);assertEquals(405,controller.dispatch(request,new MockHttpServletResponse()).getStatus().value());assertNull(request.getSession(false));}
  }
  verifyNoInteractions(store);
  assertThrows(SQLException.class,()->controller.dispatch(new MockHttpServletRequest("GET","/static/migrations.js"),new MockHttpServletResponse()));verify(store).open();
 }

 @Test void failedTransactionInitializationAlwaysClosesItsConnection() throws Exception {
  for(boolean cleanupFails:new boolean[]{false,true}){var c=mock(Connection.class);var failure=new SQLException("init");doThrow(failure).when(c).setAutoCommit(false);if(cleanupFails)doThrow(new SQLException("cleanup")).when(c).close();assertSame(failure,assertThrows(SQLException.class,()->new Store.Db(c,false)));verify(c).close();assertEquals(cleanupFails?1:0,failure.getSuppressed().length);}
 }
 @Test void onlySqliteAcquisitionContentionIsRetryableAndNeverCreatesASession() throws Exception {
  var fixture=new MigrationsTest();fixture.root=root;
  for(boolean postgres:new boolean[]{false,true}){
   var store=spy(new Store(fixture.configuration(postgres)));var controller=new WebController(store);
   for(int code:new int[]{0,5,6,261,262,19})for(String path:new String[]{"/api/now/table/incident","/record/incident/"+Values.id()}){
    var failure=new SQLException("private-host-and-password","private",code);doThrow(failure).when(store).open();var req=new MockHttpServletRequest("POST",path);var response=new MockHttpServletResponse();
    if(!postgres && java.util.Set.of(5,6).contains(code & 255)){
     var view=controller.dispatch(req,response);assertEquals(503,view.getStatus().value());assertEquals("1",response.getHeader("Retry-After"));assertEquals("no-store",response.getHeader("Cache-Control"));assertNull(req.getSession(false));
     if(path.startsWith("/api/")){view.getView().render(view.getModel(),req,response);assertFalse(response.getContentAsString().contains("private"));assertTrue(response.getContentAsString().contains("did not start"));}else{assertEquals("error",view.getViewName());assertFalse(view.getModel().toString().contains("private"));}
    }else assertSame(failure,assertThrows(SQLException.class,()->controller.dispatch(req,response)));
   }
  }
 }
}
