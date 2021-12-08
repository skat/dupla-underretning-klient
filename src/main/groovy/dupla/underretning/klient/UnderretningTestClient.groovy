package dupla.underretning.klient

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.rabbitmq.client.*
import com.rabbitmq.client.impl.ExternalMechanism
import groovy.json.JsonException
import groovy.json.JsonOutput
import org.apache.commons.text.StringEscapeUtils
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.KeyStore
import java.security.cert.X509Certificate

@SuppressWarnings('all')
class UnderretningTestClient {

    private static String aftaleId = "Mockdata-MockHent-19552101"

    private static String clientCertKeystore = "client_cert_30806460.pkcs12"
    private static char[] clientCertPassphrase = "Test1234".toCharArray()

    // brug skat_truststore.pkcs12 internt i skat
    private static String trustKeystore =  null // "skat_truststore.pkcs12"
    private static char[] trustPassphrase = "Test1234".toCharArray()

    private static String host = "underretning.dataudstilling.tfe.ocpt.ccta.dk"

    private static boolean verifyHostname = false

    static void main(String[] args) {
        ConnectionFactory factory = new ConnectionFactory()

        KeyStore ks = KeyStore.getInstance("PKCS12")
        ks.load(new FileInputStream(clientCertKeystore), clientCertPassphrase)

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509")
        kmf.init(ks, clientCertPassphrase)

        KeyStore tks = null
        if (trustKeystore) {
            tks = KeyStore.getInstance("PKCS12")
            tks.load(new FileInputStream(trustKeystore), trustPassphrase)
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509")
        tmf.init((KeyStore) tks)
        X509Certificate[] trustedCerts = ((X509TrustManager) tmf.getTrustManagers()[0]).getAcceptedIssuers()

        trustedCerts.each { X509Certificate cert ->
            println "trust cert: ${cert.getSubjectDN()}"
        }
        SSLContext c = SSLContext.getInstance("TLSv1.3")

        c.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null)

        factory.setHost(host)
        factory.setPort(443)
        factory.useSslProtocol(c)

        // det er vigtigt at forcere SASL EXTERNAL protokol for at kunne authenticate med SSL client cert
        factory.setSaslConfig(new SaslConfig() {
            @Override
            SaslMechanism getSaslMechanism(String[] strings) {
                return new ExternalMechanism()
            }
        })

        if (verifyHostname) {
            factory.enableHostnameVerification()
        }

        Connection conn = factory.newConnection()
        conn.getClientProperties().each { String key, Object value ->
            println "$key -> $value"
        }

        try {
            AMQP.Queue.DeclareOk queue
            Channel channel
            while (!queue) {
                try {
                    channel = conn.createChannel()
                    if (!channel) {
                        println "Kunne ikke oprette kanal"
                        return
                    }

                    println "Channel nummer: ${channel.getChannelNumber()}"
                    queue = channel.queueDeclarePassive(aftaleId)
                } catch (IOException ex) {
                    println "kø eksisterer ikke endnu - venter øjeblik før vi kigger igen om den skulle være dukket op"
                    Thread.sleep(10 * 60 * 1000)
                }
            }

            println "Queue name: ${queue.getQueue()}"
            ObjectMapper objectMapper = new ObjectMapper()

            channel.basicConsume(queue.getQueue(), false, "myConsumerTag",
                new DefaultConsumer(channel) {
                    @Override
                    void handleDelivery(String consumerTag,
                                        Envelope envelope,
                                        AMQP.BasicProperties properties,
                                        byte[] body) throws IOException {

                        // behandl underretning
                        try {
                            String currentRoutingKey = envelope.getRoutingKey()
                            String contentType = properties.getContentType()
                            long deliveryTag = envelope.getDeliveryTag()
                            String message = new String(body)

                            String prettyPrinted = StringEscapeUtils.unescapeJson(JsonOutput.prettyPrint(message))
                            println "modtog besked fra kø: $prettyPrinted"

                            UnderretningResurse underretning = objectMapper.readValue(message, UnderretningResurse)
                            println "parset JSON til object: $underretning"

                            channel.basicAck(deliveryTag, false)
                        } catch (JsonException ex) {
                            println "fejl ved parsing af underretning hentet på kø: $ex"
                        } catch (JacksonException ex) {
                            println "fejl ved mappning af underretning hentet på kø: $ex"
                        } catch (IOException ex) {
                            println "fejl kommunikation med kø: $ex"
                        } catch (Exception ex) {
                            println "anden fejl: $ex"
                        }
                    }
                })

            // main loop here
            while (channel.isOpen()) {
                Thread.sleep(5000)
            }

            if (channel.isOpen())
                channel.close()

        } catch (IOException ex) {
            println "Fejl ved consumption $ex.message"
            return
        }

        conn.close()
        println "Klient stoppet"
    }

}
