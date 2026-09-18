import { describe, expect, it } from "vitest";
import { validateWebPush } from "../src/web-push";
const subscription = (endpoint = "https://web.push.apple.com/example") => ({endpoint,keys:{p256dh:Buffer.alloc(65,4).toString("base64url"),auth:Buffer.alloc(16,1).toString("base64url")}});
describe("web push subscription boundary",()=>{
 it("accepts Safari push subscriptions and explicit removal",()=>{expect(validateWebPush(subscription())).toEqual(subscription());expect(validateWebPush(null)).toBeNull();});
 it.each(["http://web.push.apple.com/x","https://localhost/x","https://169.254.169.254/x","https://web.push.apple.com.evil.example/x","https://user:password@web.push.apple.com/x","https://web.push.apple.com:9000/x","https://evil.example/x"])("rejects arbitrary network target %s",url=>{expect(()=>validateWebPush(subscription(url))).toThrow();});
 it("rejects missing and malformed encryption keys",()=>{expect(()=>validateWebPush({endpoint:subscription().endpoint})).toThrow();expect(()=>validateWebPush({...subscription(),keys:{p256dh:"short",auth:"short"}})).toThrow();});
});
