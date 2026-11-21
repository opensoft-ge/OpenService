package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;
import java.util.*;
import java.math.BigDecimal;

final class PurchasingControls {
    private PurchasingControls() {}

    static String canonical(Store.Db db,Object raw) throws Exception {
        String name=WorkJobs.text(raw,160);
        String key=ConnectorSynchronization.hash(name.strip().toLowerCase(Locale.ROOT));
        return object(get(db,"product-alias",key).get("definition")).getOrDefault("product",name).toString();
    }

    static Object command(Store.Db db,Map<String,Object> body) throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);
        lock(db);
        if ("alias".equals(body.get("operation"))) {
            keys(body,"operation","alias","product","revision");
            String name=WorkJobs.text(body.get("alias"),160).strip().toLowerCase(Locale.ROOT);
            String product=WorkJobs.text(body.get("product"),160);
            save(db,"product-alias",ConnectorSynchronization.hash(name),body.get("revision"),map("alias",name,"product",product));
            return map("product",product);
        }
        boolean dated="dated_invoice".equals(body.get("operation"));
        if(dated) keys(body,"operation","order","invoice","currency","lines","pricing_date");
        else keys(body,"operation","order","invoice","currency","lines");
        String orderId=EnterpriseData.id(body.get("order"));
        var order=object(get(db,"purchase",orderId).get("definition"));
        require("approved".equals(order.get("state")),"Select an approved purchase order");
        require(order.get("currency").equals(body.get("currency")),"Invoice currency must match the order");
        String number=WorkJobs.text(body.get("invoice"),100);
        String key=ConnectorSynchronization.hash(List.of(order.get("supplier"),number));
        var lines=new LinkedHashMap<String,Object>();
        var available=object(order.getOrDefault("lines",map(Objects.toString(order.get("model")),order)));
        var billed=new HashMap<String,Integer>();
        for(var other:list(db,"invoice")) {
            var invoice=object(other.get("definition"));
            if(orderId.equals(invoice.get("order"))) {
                for(var entry:object(invoice.get("lines")).entrySet()) {
                    billed.merge(entry.getKey(),((Number)object(entry.getValue()).get("quantity")).intValue(),Integer::sum);
                }
            }
        }
        for(Object item:batch(body.get("lines"),20)) {
            var values=object(item);
            keys(values,"model","quantity","unit_cost");
            String model=WorkJobs.text(values.get("model"),160);
            require(available.containsKey(model),"Invoice line has no matching ordered model");
            int quantity=integer(values.get("quantity"),1,10000);
            var unit=money(values.get("unit_cost"));
            var line=object(available.get(model));
            int received=((List<?>)line.getOrDefault("received",List.of())).size();
            int prior=billed.getOrDefault(model,0);
            require(lines.put(model,map("quantity",quantity,"unit_cost",unit.toPlainString(),"ordered",line.get("quantity"),
                    "received",received,"cumulative_invoiced",prior+quantity,"received_minus_billed",received-prior-quantity,
                    "price_variance",unit.subtract(money(line.get("unit_cost"))).multiply(BigDecimal.valueOf(quantity)).toPlainString()))==null,
                    "Invoice each model once");
        }
        require(!lines.isEmpty(),"Provide invoice lines");
        var receipt=map("invoice",number,"supplier",order.get("supplier"),"currency",order.get("currency"),
                "order",orderId,"lines",lines,"matched_at",now(),"actor",db.viewerId);
        SupplierContracts.match(db,receipt,dated?ProjectDelivery.date(body.get("pricing_date")):java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
        save(db,"invoice",key,0,receipt);
        return receipt;
    }
}
