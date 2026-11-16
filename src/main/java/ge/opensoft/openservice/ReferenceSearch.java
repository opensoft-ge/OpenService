package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;

/** Literal summary search for a reference picker, applying the normal live table/field/record policy. */
final class ReferenceSearch {
    private ReferenceSearch() {}
    static Map<String,Object> search(Store.Db db,String table,Object input) throws SQLException {
        require(!table.isEmpty(),"Specify the referenced table");
        var args=object(input);
        require(Set.of("q","limit","offset").containsAll(args.keySet()),"Use q, limit and offset only");
        int limit=Service.number(Objects.toString(args.getOrDefault("limit","20")),1,100);
        int offset=Service.number(Objects.toString(args.getOrDefault("offset","0")),0,Integer.MAX_VALUE);
        String query=Objects.toString(args.getOrDefault("q",""));
        require(query.length()<=200 && query.indexOf('\0')<0,"Search is limited to 200 characters");
        var definition=FieldAccess.schema(db,Service.schema(db,table));
        if(!object(definition.get("fields")).containsKey("short_description"))
            throw new Failure(403,"Reference labels are not readable");
        var page=Service.page(db,table,Map.of("sysparm_limit",Integer.toString(limit),
                "sysparm_offset",Integer.toString(offset),"sysparm_fields","sys_id,short_description"),query);
        return map("items",page.rows().stream().map(row->map("value",row.get("sys_id"),"label",row.get("short_description"))).toList(),
                "total",page.total(),"limit",limit,"offset",offset,
                "next_offset",page.hasNext()?(long)offset+limit:null);
    }
}
