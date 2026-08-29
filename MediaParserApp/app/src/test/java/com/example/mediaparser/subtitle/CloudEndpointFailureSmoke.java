package com.example.mediaparser.subtitle;

/** Network smoke with deliberately invalid, non-secret credentials. Verifies endpoint/error headers only. */
public final class CloudEndpointFailureSmoke {
    public static void main(String[] args)throws Exception{
        String fake="invalid-mediaparser-probe";
        DoubaoTranscriber.Response r=DoubaoTranscriber.probe(new DoubaoCredentialStore.Credentials(fake,"","",DoubaoCredentialStore.DEFAULT_RESOURCE));
        if(r.http<200||r.http>=600)throw new AssertionError("unexpected HTTP status "+r.http);
        String visible=r.status+" "+r.message+" "+r.body;
        if(visible.contains(fake))throw new AssertionError("credential echoed in provider diagnostics");
        if("20000000".equals(r.status))throw new AssertionError("invalid credential unexpectedly accepted");
        System.out.println("Doubao endpoint reachable; rejected invalid credential with HTTP="+r.http+", providerCode="+r.status+", message="+r.message);
    }
}
