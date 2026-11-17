package ge.opensoft.openservice;
import static ge.opensoft.openservice.TypedFields.require;
import ge.opensoft.openservice.Values.Failure;
import java.net.*;
import java.util.*;
/** Deliberately bounded syntax checks, not mailbox ownership, URL safety or network lookup. */
final class ContactFields {
 private ContactFields() {}
 static void email(String value) {
  require(value.matches("(?=.{1,254}$)(?=.{1,64}@)[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}"),"Use an ASCII email address with a dotted domain (maximum 254 characters)");
 }
 static void url(String value) {
  require(value.length()<=2048,"URL must not exceed 2048 characters");
  try{var uri=new URI(value);require(Set.of("http","https").contains(Objects.toString(uri.getScheme(),"").toLowerCase(Locale.ROOT)) && uri.getHost()!=null && uri.getRawUserInfo()==null && (uri.getPort()==-1 || (uri.getPort()>=1 && uri.getPort()<=65535)),"Use an absolute HTTP or HTTPS URL without embedded credentials");}
  catch(URISyntaxException invalid){throw new Failure(400,"Provide a valid HTTP or HTTPS URL");}
 }
}
