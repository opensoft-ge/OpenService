package ge.opensoft.openservice;
import static ge.opensoft.openservice.Values.*;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.commons.csv.*;
/** Fresh authorized aggregates, with literal spreadsheet labels and exact numeric strings. */
final class ReportExport {
 private ReportExport() {}
 static final List<String> COLUMNS=List.of("group","count","value_count","sum","min","max","average");
 static final Pattern FORMULA=Pattern.compile("(?s)^[\\s\\p{Z}\\p{Cf}]*[=+@\\-＝＋＠－\\t\\r\\n].*");
 static byte[] csv(Store.Db db,String actor,String id) throws Exception {
  var result=SavedReports.run(db,actor,id);var output=new StringWriter();
  try(var printer=new CSVPrinter(output,CSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL).get())){
   printer.printRecord(COLUMNS);
   for(var row:objects(result.get("rows"))){var cells=new ArrayList<Object>();String label=row.get("group").toString();cells.add(FORMULA.matcher(label).matches()?"'"+label:label);for(String column:COLUMNS.subList(1,COLUMNS.size()))cells.add(row.get(column));printer.printRecord(cells);}
  }
  return output.toString().getBytes(StandardCharsets.UTF_8);
 }
}
