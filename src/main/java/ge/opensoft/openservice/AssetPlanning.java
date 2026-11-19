package ge.opensoft.openservice;

import static ge.opensoft.openservice.Values.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.EnterpriseData.*;
import java.util.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.math.*;

/** Local purchasing, acquisition values and seat entitlements; no vendor purchasing side effects. */
final class AssetPlanning {
    private AssetPlanning() {}
    static Map<String,Object> page(Store.Db db)throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);var costs=list(db,"asset-cost");for(var cost:costs){AssetCustody.record(db,cost.get("subject").toString());cost.put("book_value",book(object(cost.get("definition")),LocalDate.now()));}
        return EnterpriseData.page("Purchasing and software entitlements","Approve a purchase, receive registered assets into stock and retain acquisition values. Entitlements record local seat rights; they do not install or remove software.",map("orders",list(db,"purchase"),"entitlements",list(db,"entitlement"),"asset_values",costs,"reconciliation",SoftwareInventory.reconcile(db),"aliases",list(db,"product-alias"),"invoices",list(db,"invoice")),
            form("order","Create purchase order",field("key","Unique order key","text",Values.id()),field("supplier","Supplier","text",""),field("model","Asset model","text",""),field("quantity","Quantity","number",1),field("unit_cost","Unit cost","text","0.00"),field("currency","Currency code","text","USD"),field("months","Useful life in months","number",36),field("salvage","Residual value per asset","text","0.00")),
            form("multi_order","Create multi-line purchase order",field("key","Unique order key","text",Values.id()),field("supplier","Supplier","text",""),field("currency","Order currency","text","USD"),field("lines","Lines with model, quantity, unit_cost, months and salvage","json","[]")),
            form("approve","Approve purchase order",field("order_id","Order key","text",""),field("revision","Order revision","number",1)),
            form("receive","Receive one registered asset",field("order_id","Order key","text",""),field("revision","Order revision","number",2),field("asset_id","Registered asset ID","text",""),field("stockroom_id","Stockroom ID","text",""),field("received_on","Receipt date","text",LocalDate.now().toString())),
            form("alias","Match discovered product name",field("alias","Discovered name","text",""),field("product","Entitlement product name","text",""),field("revision","Current revision","number",0)),
            form("invoice","Match supplier invoice",field("order","Approved order ID","text",""),field("invoice","Supplier invoice number","text",""),field("currency","Currency","text","USD"),field("lines","Invoice lines (model, quantity, unit_cost)","json","[]")),
            form("inventory","Replace discovered installations for one product",field("product","Exact entitlement product name","text",""),field("revision","Inventory revision","number",0),field("installations","Complete snapshot of asset_id and user_id pairs","json","[]")),
            form("license","Register software entitlement",field("key","Unique entitlement key","text",Values.id()),field("product","Product","text",""),field("seats","Purchased seats","number",1),field("expires_on","Expiry date (inclusive)","text",LocalDate.now().plusYears(1).toString())),
            form("allocate","Allocate a software seat",field("license_id","Entitlement key","text",""),field("revision","Entitlement revision","number",1),field("user_id","Account ID","text","")),
            form("release","Release a software seat",field("license_id","Entitlement key","text",""),field("revision","Entitlement revision","number",1),field("user_id","Account ID","text","")));
    }
    private static Map<String,Object> purchaseLine(Map<String,Object> fields) {
        var unitCost = money(fields.get("unit_cost"));
        var residual = money(fields.get("salvage"));
        require(residual.compareTo(unitCost) <= 0, "Residual value cannot exceed acquisition cost");
        return map("model",WorkJobs.text(fields.get("model"),160), "quantity",integer(fields.get("quantity"),1,100),
                "unit_cost",unitCost.toPlainString(), "months",integer(fields.get("months"),1,600),
                "salvage",residual.toPlainString());
    }
    static String book(Map<String,Object> cost,LocalDate date){long elapsed=Math.max(0,ChronoUnit.MONTHS.between(LocalDate.parse(cost.get("received_on").toString()),date));int months=((Number)cost.get("months")).intValue();BigDecimal initial=money(cost.get("unit_cost")),salvage=money(cost.get("salvage"));return initial.subtract(initial.subtract(salvage).multiply(BigDecimal.valueOf(Math.min(elapsed,months))).divide(BigDecimal.valueOf(months),2,RoundingMode.HALF_UP)).toPlainString();}
    static void command(Store.Db db,Object input)throws Exception {
        Service.requireAccountAdministrator(db,db.viewerId);lock(db);var body=object(input);String op=WorkJobs.text(body.get("operation"),12);
        if (op.equals("inventory")) {
            SoftwareInventory.replace(db, body);
            return;
        }
        if (Set.of("order","multi_order").contains(op)) {
            String currency = WorkJobs.text(body.get("currency"), 3);
            require(currency.matches("[A-Z]{3}"), "Use a three-letter currency code");
            var order = map("supplier", WorkJobs.text(body.get("supplier"),160), "currency", currency,
                    "state","draft", "received", new ArrayList<String>());
            if (op.equals("order")) {
                keys(body,"operation","key","supplier","currency","model","quantity","unit_cost","months","salvage");
                order.putAll(purchaseLine(body));
            } else {
                keys(body,"operation","key","supplier","currency","lines");
                var lines = new LinkedHashMap<String,Object>();
                for (Object value : EnterpriseData.batch(body.get("lines"), 20)) {
                    var fields = object(value);
                    keys(fields,"model","quantity","unit_cost","months","salvage");
                    var line = purchaseLine(fields);
                    require(lines.put(line.get("model").toString(), line) == null, "Use distinct models per order");
                }
                require(!lines.isEmpty(), "Provide at least one purchase line");
                order.put("lines", lines);
            }
            save(db,"purchase",EnterpriseData.id(body.get("key")),0,order);
            return;
        }
        if(Set.of("approve","receive").contains(op)){
            keys(body,op.equals("approve")?new String[]{"operation","order_id","revision"}:new String[]{"operation","order_id","revision","asset_id","stockroom_id","received_on"});String key=EnterpriseData.id(body.get("order_id"));var row=get(db,"purchase",key);var data=object(row.get("definition"));require(!data.isEmpty(),"Purchase order not found");ProblemInvestigations.revision(body.get("revision"),row.get("revision"));
            if(op.equals("approve")){require(data.get("state").equals("draft"),"Approve a draft purchase");data.put("state","approved");data.put("approved_by",db.viewerId);}else{
                require(data.get("state").equals("approved"),"Receive against an approved purchase");String asset=EnterpriseData.id(body.get("asset_id"));var record=AssetCustody.record(db,asset);String model = object(record.get("data")).get("model").toString();
                var line = data;
                if (data.containsKey("lines")) {
                    var lines = object(data.get("lines"));
                    require(lines.containsKey(model), "Asset model is absent from this purchase");
                    line = object(lines.get(model));
                }
                var received = new ArrayList<Object>((List<?>)line.getOrDefault("received",List.of()));
                require(!received.contains(asset) && received.size()<((Number)line.get("quantity")).intValue(),"Asset already received or order quantity exhausted");
                require(line.get("model").equals(model),"Asset model must match the purchase");
                String date=ProjectDelivery.date(body.get("received_on"));require(!LocalDate.parse(date).isAfter(LocalDate.now()),"Receipt date cannot be in the future");
                AssetCustody.save(db,asset,map("operation","receive","revision",AssetCustody.latest(db,asset).get("revision"),"record_revision",record.get("revision"),"reason","Received against approved purchase "+key,"stockroom_id",EnterpriseData.id(body.get("stockroom_id")),"ci_id",""));
                save(db,"asset-cost",asset,0,map("order_id",key,"unit_cost",line.get("unit_cost"),"currency",data.get("currency"),"salvage",line.get("salvage"),"months",line.get("months"),"received_on",date));received.add(asset);
                line.put("received",received);
                if (data.containsKey("lines")) {
                    var allReceived = new ArrayList<Object>((List<?>)data.get("received"));
                    allReceived.add(asset);
                    data.put("received",allReceived);
                }
            }save(db,"purchase",key,row.get("revision"),data);return;
        }
        if(op.equals("license")){
            keys(body,"operation","key","product","seats","expires_on");String date=ProjectDelivery.date(body.get("expires_on"));require(!LocalDate.parse(date).isBefore(LocalDate.now()),"Register a current entitlement");save(db,"entitlement",EnterpriseData.id(body.get("key")),0,map("product",WorkJobs.text(body.get("product"),160),"seats",integer(body.get("seats"),1,10000),"expires_on",date,"users",new ArrayList<String>()));return;
        }
        require(Set.of("allocate","release").contains(op),"Choose a supported purchasing or entitlement operation");keys(body,"operation","license_id","revision","user_id");String key=EnterpriseData.id(body.get("license_id")),user=EnterpriseData.id(body.get("user_id"));var row=get(db,"entitlement",key);var data=object(row.get("definition"));require(!data.isEmpty(),"Entitlement not found");ProblemInvestigations.revision(body.get("revision"),row.get("revision"));var users=new ArrayList<Object>((List<?>)data.get("users"));
        if(op.equals("allocate")){require(!LocalDate.parse(data.get("expires_on").toString()).isBefore(LocalDate.now()),"Entitlement expired");require(db.count("SELECT COUNT(*) FROM os_user WHERE user_id=? AND active=1",user)==1,"Choose an active account");require(!users.contains(user) && users.size()<((Number)data.get("seats")).intValue(),"Seat already allocated or entitlement exhausted");users.add(user);}else require(users.remove(user),"This account has no allocated seat");data.put("users",users);save(db,"entitlement",key,row.get("revision"),data);
    }
}
