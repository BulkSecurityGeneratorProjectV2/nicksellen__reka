package reka;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import reka.api.Path;
import reka.api.flow.Flow;
import reka.core.builder.FlowVisualizer;
import reka.core.builder.Flows;
import reka.core.bundle.PortAndProtocol;

public class Application {

	private final List<DeployedResource> resources;
	private final List<PortAndProtocol> ports; 

	private final Path name;
	private final String fullName;
	private final int version;
	private final Flows flows;
	private final FlowVisualizer initializerVisualizer;
	
	public Application(
			Path name, 
			int version, 
			Flows flows,  
			List<PortAndProtocol> ports, 
			FlowVisualizer initializerVisualizer,
			List<DeployedResource> resources) {
		this.name = name;
		this.fullName = name.slashes();
		this.version = version;
		this.flows = flows;
		this.ports = ports;
		this.initializerVisualizer = initializerVisualizer;
		this.resources = resources;
	}
		
	public Path name() {
		return name;
	}
	
	public String fullName() {
		return fullName;
	}
	
	public int version() {
		return version;
	}
	
	public Flows flows() {
		return flows;
	}
	
	public List<PortAndProtocol> ports() {
		return ports;
	}
	
	public FlowVisualizer initializerVisualizer() {
		return initializerVisualizer;
	}
	
	public Collection<Path> flowNames() {
		List<Path> names = new ArrayList<>();
		for (Flow flow : flows.flows()) {
			names.add(flow.name());
		}
		return names;
	}
	
	public void undeploy() {
		for (DeployedResource resource : resources) {
			try {
				resource.undeploy(version);
			} catch (Throwable t) {
				t.printStackTrace();
			}			
		}
	}

	public void freeze() {
		for (DeployedResource resource : resources) {
			try {
				resource.freeze(version);
			} catch (Throwable t) {
				t.printStackTrace();
			}			
		}		
	}

}
