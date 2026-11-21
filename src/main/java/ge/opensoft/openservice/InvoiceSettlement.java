package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.JourneyForms.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

final class InvoiceSettlement {
    private InvoiceSettlement() {}

    static Object command(Store.Db db,String op,Map<String,Object> b)throws Exception {
        admin(db);
        if(op.equals("settle")) return settle(db,b);
        if(op.equals("accept_price")){
            String invoiceId=text(b,"invoice_id",64),model=text(b,"model",160);
            var invoice=definition(db,"invoice",invoiceId);
            require(!db.viewerId.equals(invoice.get("actor")),"A different administrator must approve the variance");
            var lines=object(invoice.get("lines"));
            require(lines.containsKey(model),"Choose an invoiced model");
            var line=object(lines.get(model));
            require(new BigDecimal(line.getOrDefault("contract_unit_variance",line.get("price_variance")).toString()).signum()!=0 && money(b.get("unit_cost")).equals(money(line.get("unit_cost"))),"Review the invoiced price difference");
            String key=ConnectorSynchronization.hash(List.of(invoiceId,model));
            save(db,"finance-price",key,0,map("invoice",invoiceId,"model",model,"unit_cost",line.get("unit_cost"),"approved_by",db.viewerId,"reason",text(b,"note",500)));
            return get(db,"finance-price",key);
        }
        String product=PurchasingControls.canonical(db,b.get("product"));
        String key=ConnectorSynchronization.hash(product);
        if(op.equals("seat_usage")){
            LocalDate today=LocalDate.now();
            LocalDate date=LocalDate.parse(ProjectDelivery.date(b.get("as_of")));
            require(!date.isAfter(today) && !date.isBefore(today.minusDays(7)),"Choose a publisher report from the last seven days");
            var prior=object(get(db,"seat-usage",key).get("definition"));
            require(date.toString().compareTo(prior.getOrDefault("as_of","").toString())>=0,"An older report cannot overwrite newer publisher activity");
            var observations=map();
            for(var item:batch(b.get("observations"),200)){
                var usage=object(item);
                keys(usage,"user_id","last_active");
                String user=id(usage,"user_id");
                Service.account(db,user);
                LocalDate last=LocalDate.parse(ProjectDelivery.date(usage.get("last_active")));
                require(!last.isAfter(date),"Usage cannot follow the report date");
                require(observations.put(user,last.toString())==null,"Duplicate subscriber");
            }
            save(db,"seat-usage",key,b.get("revision"),map("source",text(b,"source",160),"users",observations,"as_of",date.toString(),"received",today.toString()));
            return get(db,"seat-usage",key);
        }
        var usage=get(db,"seat-usage",key);
        var snapshot=definition(db,"seat-usage",key);
        var users=object(snapshot.get("users"));
        var candidates=new ArrayList<Map<String,Object>>();
        LocalDate today=LocalDate.now();
        require(!LocalDate.parse(ProjectDelivery.date(snapshot.get("as_of"))).isBefore(today.minusDays(7)),"Refresh publisher usage");
        for(var license:list(db,"entitlement")){
            var rights=object(license.get("definition"));
            if(!product.equals(rights.get("product")) || LocalDate.parse(rights.get("expires_on").toString()).isBefore(today)) continue;
            for(Object user:(List<?>)rights.get("users")){
                if(users.containsKey(user) && LocalDate.parse(users.get(user).toString()).isBefore(today.minusDays(90)))
                    candidates.add(map("license_id",license.get("subject"),"revision",license.get("revision"),"user_id",user,"last_active",users.get(user)));
            }
        }
        if(op.equals("reclaim")) return map("usage",usage,"candidates",candidates);
        ProblemInvestigations.revision(b.get("usage_revision"),usage.get("revision"));
        require(Boolean.TRUE.equals(b.get("confirm")) && candidates.stream().anyMatch(c->c.get("license_id").equals(b.get("license_id")) && c.get("user_id").equals(b.get("user_id"))),"Select a confirmed inactive seat");
        SeatRecovery.allowed(db,id(b,"license_id"),id(b,"user_id"));
        AssetPlanning.command(db,map("operation","release","license_id",id(b,"license_id"),"revision",b.get("revision"),"user_id",id(b,"user_id")));
        return get(db,"entitlement",id(b,"license_id"));
    }

    static Object settle(Store.Db db,Map<String,Object> b)throws Exception {
        String key=text(b,"invoice_id",64);
        var invoice=definition(db,"invoice",key);
        var order=definition(db,"purchase",invoice.get("order").toString());
        var orderLines=object(order.getOrDefault("lines",map(Objects.toString(order.get("model")),order)));
        var old=get(db,"finance-settlement",key);
        var settled=object(old.get("definition"));
        var exports=new ArrayList<>(objects(settled.getOrDefault("exports",List.of())));
        var paid=object(settled.getOrDefault("paid",map()));
        var holds=map();
        var charges=map();
        for(var entry:object(invoice.get("lines")).entrySet()){
            String model=entry.getKey();
            var billed=object(entry.getValue());
            var ordered=object(orderLines.get(model));
            int quantity=(int)billed.get("quantity");
            int prior=(int)billed.get("cumulative_invoiced")-quantity;
            int received=((List<?>)ordered.getOrDefault("received",List.of())).size();
            int eligible=Math.min(quantity,Math.max(0,received-prior));
            boolean price=money(billed.getOrDefault("contract_unit_cost",ordered.get("unit_cost"))).compareTo(money(billed.get("unit_cost")))==0;
            if(!price){
                var approval=object(get(db,"finance-price",ConnectorSynchronization.hash(List.of(key,model))).get("definition"));
                price=billed.get("unit_cost").equals(approval.get("unit_cost"));
            }
            if(!price) eligible=0;
            int delta=eligible-(int)paid.getOrDefault(model,0);
            if(delta>0){
                charges.put(model,map("quantity",delta,"amount",money(billed.get("unit_cost")).multiply(BigDecimal.valueOf(delta)).toPlainString()));
                paid.put(model,eligible);
            }
            if(eligible<quantity) holds.put(model,map("quantity",quantity-eligible,"reason",price?"awaiting_receipt":"price_variance"));
        }
        if(!charges.isEmpty()) exports.add(map("export_id",Values.id(),"currency",invoice.get("currency"),"supplier",invoice.get("supplier"),"invoice",invoice.get("invoice"),"lines",charges,"at",now()));
        var next=map("paid",paid,"holds",holds,"exports",exports);
        if(!next.equals(settled)) save(db,"finance-settlement",key,b.get("revision"),next);
        return get(db,"finance-settlement",key);
    }
    static byte[] export(Store.Db db,String invoice,String batch)throws Exception{
        admin(db);
        var ledger=definition(db,"finance-settlement",invoice);
        var item=objects(ledger.get("exports")).stream().filter(e->batch.equals(e.get("export_id"))).findFirst().orElseThrow(()->new Failure(404,"Export batch unavailable"));
        var buffer=new StringBuilder();
        try(var csv=new org.apache.commons.csv.CSVPrinter(buffer,org.apache.commons.csv.CSVFormat.RFC4180)){
            csv.printRecord("batch","supplier","invoice","currency","model","quantity","amount");
            for(var entry:object(item.get("lines")).entrySet()){
                var line=object(entry.getValue());
                csv.printRecord(batch,spreadsheet(item.get("supplier")),spreadsheet(item.get("invoice")),item.get("currency"),spreadsheet(entry.getKey()),line.get("quantity"),line.get("amount"));
            }
        }
        return buffer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static String spreadsheet(Object value){
        String text=value.toString();
        return text.stripLeading().matches("(?s)^[=+@-].*")?"'"+text:text;
    }
}
