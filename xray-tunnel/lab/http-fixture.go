package main
import("net/http";"io";"fmt")
func main(){fmt.Println("GRAPHITE_XRAY_HTTP_READY");http.HandleFunc("/",func(w http.ResponseWriter,r *http.Request){w.Header().Set("Content-Type","text/plain");io.WriteString(w,"GRAPHITE_XRAY_LAB_OK\n")});panic(http.ListenAndServe("127.0.0.1:18080",nil))}
